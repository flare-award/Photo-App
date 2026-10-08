package com.flareaward.serendip.camera

import android.content.Context
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import com.flareaward.serendip.domain.camera.CameraCaptureSource
import com.flareaward.serendip.domain.camera.CaptureRequest
import com.flareaward.serendip.domain.camera.CaptureResult
import com.flareaward.serendip.domain.model.FailureReason
import com.flareaward.serendip.storage.MediaStorePhotoStorage
import com.flareaward.serendip.system.AppLog
import com.flareaward.serendip.system.PermissionChecker
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * One-shot still capture with CameraX: binds an [ImageCapture] use case (no
 * preview, no analysis, no audio) to a private lifecycle, takes a single
 * picture straight into MediaStore, verifies the file and unbinds again.
 *
 * Flash is forced off and the app never plays a shutter sound; where the
 * platform enforces a shutter sound (regional requirement) it is left alone.
 */
class CameraXCaptureSource(
    private val context: Context,
    private val storage: MediaStorePhotoStorage,
    private val permissions: PermissionChecker,
) : CameraCaptureSource {

    override suspend fun capture(request: CaptureRequest): CaptureResult = withContext(Dispatchers.Main.immediate) {
        if (!permissions.hasCameraPermission()) {
            return@withContext CaptureResult.Failure(FailureReason.CAMERA_PERMISSION_MISSING)
        }

        val provider = try {
            withTimeout(PROVIDER_TIMEOUT_MS) { ProcessCameraProvider.awaitInstance(context) }
        } catch (_: TimeoutCancellationException) {
            return@withContext CaptureResult.Failure(FailureReason.CAMERA_UNAVAILABLE, "camera provider timeout")
        } catch (error: Exception) {
            AppLog.e("CameraX provider failed", error)
            return@withContext CaptureResult.Failure(FailureReason.CAMERA_ERROR, error.toString())
        }

        val hasBackCamera = try {
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        } catch (_: CameraInfoUnavailableException) {
            false
        }
        if (!hasBackCamera) return@withContext CaptureResult.Failure(FailureReason.NO_BACK_CAMERA)

        val owner = CaptureLifecycleOwner()
        var lastStateError: CameraState.StateError? = null
        try {
            provider.unbindAll()
            owner.start()
            val imageCapture = buildImageCapture()
            val camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture)
            val outputOptions = ImageCapture.OutputFileOptions
                .Builder(context.contentResolver, storage.collectionUri, storage.newPhotoValues(request.triggeredAt))
                .build()

            val saved = withTimeout(CAPTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val stateObserver = Observer<CameraState> { state ->
                        val error = state.error ?: return@Observer
                        lastStateError = error
                        AppLog.w("Camera state error ${error.code} (${state.type})", error.cause)
                        if (error.code in FATAL_STATE_ERRORS && continuation.isActive) {
                            continuation.resumeWithException(CameraStateException(error))
                        }
                    }
                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                if (continuation.isActive) {
                                    continuation.resume(outputFileResults)
                                } else {
                                    // Arrived after the timeout: the attempt already counts as failed,
                                    // so the late file must not linger as an unrecorded photo.
                                    outputFileResults.savedUri?.let { late ->
                                        runCatching { context.contentResolver.delete(late, null, null) }
                                    }
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                if (continuation.isActive) continuation.resumeWithException(exception)
                            }
                        },
                    )
                    // Fail fast when the camera cannot be opened at all (busy, disabled by policy,
                    // blocked for background use) instead of waiting for the timeout.
                    camera.cameraInfo.cameraState.observe(owner, stateObserver)
                }
            }

            val uri = saved.savedUri
                ?: return@withContext CaptureResult.Failure(FailureReason.STORAGE_WRITE_FAILED, "no saved uri")
            val info = storage.verify(uri)
            if (info == null) {
                storage.delete(uri)
                CaptureResult.Failure(FailureReason.IMAGE_INVALID, "verification failed for $uri")
            } else {
                CaptureResult.Success(
                    photoUri = uri.toString(),
                    sizeBytes = info.sizeBytes,
                    width = info.width,
                    height = info.height,
                )
            }
        } catch (error: CameraStateException) {
            CaptureResult.Failure(mapStateError(error.error.code), "camera state ${error.error.code}")
        } catch (error: ImageCaptureException) {
            AppLog.w("takePicture failed: ${error.imageCaptureError}", error)
            CaptureResult.Failure(mapCaptureError(error.imageCaptureError, lastStateError), error.toString())
        } catch (_: TimeoutCancellationException) {
            val reason = lastStateError?.let { mapStateError(it.code) } ?: FailureReason.CAPTURE_TIMEOUT
            CaptureResult.Failure(reason, "capture timeout")
        } catch (error: SecurityException) {
            AppLog.w("Camera access denied", error)
            CaptureResult.Failure(FailureReason.CAMERA_PERMISSION_MISSING, error.toString())
        } catch (error: IllegalArgumentException) {
            AppLog.w("Camera could not be bound", error)
            CaptureResult.Failure(FailureReason.CAMERA_UNAVAILABLE, error.toString())
        } catch (error: IllegalStateException) {
            AppLog.w("Camera in an unexpected state", error)
            CaptureResult.Failure(FailureReason.CAMERA_ERROR, error.toString())
        } catch (error: Exception) {
            AppLog.e("Unexpected capture failure", error)
            CaptureResult.Failure(FailureReason.UNKNOWN, error.toString())
        } finally {
            try {
                provider.unbindAll()
            } catch (error: Exception) {
                AppLog.w("unbindAll failed", error)
            }
            owner.destroy()
        }
    }

    private fun buildImageCapture(): ImageCapture {
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(MAX_RESOLUTION, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .build()
        return ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setJpegQuality(JPEG_QUALITY)
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
    }

    private fun mapCaptureError(code: Int, stateError: CameraState.StateError?): FailureReason = when (code) {
        ImageCapture.ERROR_FILE_IO -> FailureReason.STORAGE_WRITE_FAILED
        ImageCapture.ERROR_CAPTURE_FAILED -> FailureReason.CAMERA_ERROR
        ImageCapture.ERROR_INVALID_CAMERA -> FailureReason.CAMERA_UNAVAILABLE
        ImageCapture.ERROR_CAMERA_CLOSED -> stateError?.let { mapStateError(it.code) } ?: FailureReason.CAMERA_UNAVAILABLE
        else -> stateError?.let { mapStateError(it.code) } ?: FailureReason.UNKNOWN
    }

    private fun mapStateError(code: Int): FailureReason = when (code) {
        CameraState.ERROR_CAMERA_DISABLED -> FailureReason.CAMERA_ACCESS_BLOCKED
        CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> FailureReason.CAMERA_IN_USE
        CameraState.ERROR_CAMERA_FATAL_ERROR -> FailureReason.CAMERA_ERROR
        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> FailureReason.CAMERA_UNAVAILABLE
        else -> FailureReason.CAMERA_UNAVAILABLE
    }

    private class CameraStateException(val error: CameraState.StateError) : RuntimeException("camera state error ${error.code}")

    /** A tiny lifecycle that lives exactly as long as one capture. Must be driven on the main thread. */
    private class CaptureLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle get() = registry

        fun start() {
            registry.currentState = Lifecycle.State.STARTED
        }

        fun destroy() {
            if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                registry.currentState = Lifecycle.State.DESTROYED
            }
        }
    }

    private companion object {
        const val PROVIDER_TIMEOUT_MS = 10_000L
        const val CAPTURE_TIMEOUT_MS = 25_000L
        const val JPEG_QUALITY = 92
        val MAX_RESOLUTION = Size(4032, 3024)
        val FATAL_STATE_ERRORS = setOf(
            CameraState.ERROR_CAMERA_DISABLED,
            CameraState.ERROR_CAMERA_IN_USE,
            CameraState.ERROR_MAX_CAMERAS_IN_USE,
            CameraState.ERROR_CAMERA_FATAL_ERROR,
            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED,
        )
    }
}

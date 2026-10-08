# Room schemas

`SerendipDatabase` is declared with `exportSchema = true` and the KSP argument
`room.schemaLocation` points here, so every build writes
`com.flareaward.serendip.data.db.SerendipDatabase/<version>.json` into this
directory. Commit those files: they are the reference Room uses to validate
future migrations (the current schema is version 1 and there are no migrations yet).

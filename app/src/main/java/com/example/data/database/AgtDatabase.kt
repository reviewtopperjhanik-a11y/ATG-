package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProjectEntity::class,
        TaskEntity::class,
        CheckpointEntity::class,
        CachedModelEntity::class,
        ExtensionEntity::class,
        ChatMessageEntity::class,
        TaskStateSnapshotEntity::class,
        ProjectMemoryEntity::class,
        FlightRecorderEventEntity::class,
        ProjectKnowledgeEntity::class,
        CouncilSessionEntity::class,
        CouncilRoundEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AgtDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun taskDao(): TaskDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun cachedModelDao(): CachedModelDao
    abstract fun extensionDao(): ExtensionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun taskStateSnapshotDao(): TaskStateSnapshotDao
    abstract fun projectMemoryDao(): ProjectMemoryDao
    abstract fun flightRecorderDao(): FlightRecorderDao
    abstract fun projectKnowledgeDao(): ProjectKnowledgeDao
    abstract fun councilDao(): CouncilDao

    companion object {
        const val FTS5_TABLE_NAME = "project_knowledge_fts"
        const val FTS4_TABLE_NAME = "project_knowledge_fts_v4"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_models` (
                        `id` TEXT NOT NULL,
                        `provider` TEXT NOT NULL,
                        `supportsStreaming` INTEGER,
                        `supportsToolCalling` INTEGER,
                        `supportsVision` INTEGER,
                        `contextWindow` INTEGER,
                        `lastSyncedEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_tasks` ADD COLUMN `summaryJson` TEXT")
                db.execSQL("ALTER TABLE `checkpoints` ADD COLUMN `fileCount` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `extensions` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `publisher` TEXT NOT NULL,
                        `version` TEXT NOT NULL,
                        `availableVersion` TEXT,
                        `license` TEXT NOT NULL,
                        `compatibility` TEXT NOT NULL,
                        `sourceUri` TEXT NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `permissionsCsv` TEXT NOT NULL,
                        `installState` TEXT NOT NULL,
                        `isBuiltIn` INTEGER NOT NULL,
                        `endpointUrl` TEXT,
                        `skillContentPath` TEXT,
                        `discoveredToolsJson` TEXT,
                        `healthMessage` TEXT,
                        `installedAtEpochMs` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_messages` (
                        `id` TEXT NOT NULL,
                        `projectId` TEXT NOT NULL,
                        `taskId` TEXT,
                        `role` TEXT NOT NULL,
                        `title` TEXT,
                        `content` TEXT NOT NULL,
                        `modelId` TEXT,
                        `backendId` TEXT,
                        `promptTokens` INTEGER,
                        `completionTokens` INTEGER,
                        `isWarning` INTEGER NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_projectId` ON `chat_messages` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_taskId` ON `chat_messages` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_projectId_createdAtEpochMs` ON `chat_messages` (`projectId`, `createdAtEpochMs`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `task_state_snapshots` (
                        `taskId` TEXT NOT NULL,
                        `projectId` TEXT NOT NULL,
                        `objective` TEXT NOT NULL,
                        `currentState` TEXT NOT NULL,
                        `activeStepIndex` INTEGER NOT NULL,
                        `serializedPlanStepsJson` TEXT NOT NULL,
                        `keyDecisionsJson` TEXT NOT NULL,
                        `modifiedFilesJson` TEXT NOT NULL,
                        `resumePayloadJson` TEXT NOT NULL,
                        `lastModelId` TEXT,
                        `lastBackendId` TEXT,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`taskId`),
                        FOREIGN KEY(`taskId`) REFERENCES `agent_tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_state_snapshots_projectId` ON `task_state_snapshots` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_state_snapshots_projectId_updatedAtEpochMs` ON `task_state_snapshots` (`projectId`, `updatedAtEpochMs`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `project_memory_entries` (
                        `projectId` TEXT NOT NULL,
                        `stackLabel` TEXT NOT NULL,
                        `curatedRulesJson` TEXT NOT NULL,
                        `validationMetadataJson` TEXT NOT NULL,
                        `repoMapCache` TEXT NOT NULL,
                        `repoMapUpdatedAtEpochMs` INTEGER NOT NULL,
                        `knownFixesJson` TEXT NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`projectId`),
                        FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `flight_recorder_events` (
                        `id` TEXT NOT NULL,
                        `projectId` TEXT NOT NULL,
                        `taskId` TEXT NOT NULL,
                        `seq` INTEGER NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `toolName` TEXT,
                        `commandOrPath` TEXT,
                        `outcome` TEXT NOT NULL,
                        `exitCode` INTEGER,
                        `outputExcerpt` TEXT NOT NULL,
                        `modelId` TEXT,
                        `backendId` TEXT,
                        `stepIndex` INTEGER,
                        `checkpointId` TEXT,
                        `timestampMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_flight_recorder_events_projectId` ON `flight_recorder_events` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_flight_recorder_events_taskId_seq` ON `flight_recorder_events` (`taskId`, `seq`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_flight_recorder_events_projectId_timestampMs` ON `flight_recorder_events` (`projectId`, `timestampMs`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `project_knowledge_entries` (
                        `id` TEXT NOT NULL,
                        `projectId` TEXT NOT NULL,
                        `factKey` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `tags` TEXT NOT NULL,
                        `sourceTaskId` TEXT,
                        `confidence` REAL NOT NULL,
                        `validFromEpochMs` INTEGER NOT NULL,
                        `validUntilEpochMs` INTEGER,
                        `supersededById` TEXT,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_knowledge_entries_projectId` ON `project_knowledge_entries` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_knowledge_entries_projectId_factKey_supersededById` ON `project_knowledge_entries` (`projectId`, `factKey`, `supersededById`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_knowledge_entries_projectId_category_validUntilEpochMs` ON `project_knowledge_entries` (`projectId`, `category`, `validUntilEpochMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_knowledge_entries_projectId_updatedAtEpochMs` ON `project_knowledge_entries` (`projectId`, `updatedAtEpochMs`)")
                ensureKnowledgeFtsIndex(db)
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `council_sessions` (
                        `sessionId` TEXT NOT NULL,
                        `projectId` TEXT NOT NULL,
                        `taskId` TEXT NOT NULL,
                        `objective` TEXT NOT NULL,
                        `mode` TEXT NOT NULL,
                        `currentRoundNumber` INTEGER NOT NULL,
                        `approvedForCoding` INTEGER NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`),
                        FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_sessions_projectId` ON `council_sessions` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_sessions_taskId` ON `council_sessions` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_sessions_projectId_updatedAtEpochMs` ON `council_sessions` (`projectId`, `updatedAtEpochMs`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `council_rounds` (
                        `id` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `roundNumber` INTEGER NOT NULL,
                        `userAmendmentNote` TEXT,
                        `refreshedSearch` INTEGER NOT NULL,
                        `decompositionJson` TEXT NOT NULL,
                        `dossierJson` TEXT NOT NULL,
                        `opinionsJson` TEXT NOT NULL,
                        `verdict` TEXT NOT NULL,
                        `sectionsJson` TEXT NOT NULL,
                        `totalPromptTokens` INTEGER NOT NULL,
                        `totalCompletionTokens` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`sessionId`) REFERENCES `council_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_council_rounds_sessionId` ON `council_rounds` (`sessionId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_council_rounds_sessionId_roundNumber` ON `council_rounds` (`sessionId`, `roundNumber`)")
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
        )

        /**
         * Ensures SQLite FTS5 virtual table (`project_knowledge_fts`) — or FTS4 (`project_knowledge_fts_v4`)
         * on Android builds compiled without FTS5 — and automatic sync triggers exist for
         * `project_knowledge_entries`. Probes `PRAGMA compile_options` before issuing `USING fts5`
         * to avoid native `E/SQLiteLog` statement-abort errors on stock Android SQLite.
         */
        fun ensureKnowledgeFtsIndex(db: SupportSQLiteDatabase): Boolean {
            val hasTable = db.query(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='project_knowledge_entries' LIMIT 1"
            ).use { it.moveToFirst() }
            if (!hasTable) return false

            if (isFts5Active(db)) {
                installFts5Triggers(db)
                return true
            }

            if (isFts4FallbackActive(db)) {
                installFts4Triggers(db)
                return false
            }

            if (supportsFts5(db)) {
                val fts5Installed = runCatching {
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS `$FTS5_TABLE_NAME` USING fts5(
                            entryId UNINDEXED,
                            projectId UNINDEXED,
                            title,
                            content,
                            tags,
                            factKey,
                            tokenize='porter unicode61'
                        )
                        """.trimIndent()
                    )
                    installFts5Triggers(db)
                    db.execSQL(
                        """
                        INSERT INTO `$FTS5_TABLE_NAME` (`entryId`, `projectId`, `title`, `content`, `tags`, `factKey`)
                        SELECT `id`, `projectId`, `title`, `content`, `tags`, `factKey`
                        FROM `project_knowledge_entries`
                        WHERE `id` NOT IN (SELECT `entryId` FROM `$FTS5_TABLE_NAME`)
                        """.trimIndent()
                    )
                    true
                }.getOrDefault(false)

                if (fts5Installed) return true
            }

            // Fallback to FTS4 cleanly on stock Android SQLite builds compiled without FTS5
            installFts4Fallback(db)
            return false
        }

        fun installFts4Fallback(db: SupportSQLiteDatabase): Boolean = runCatching {
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS `$FTS4_TABLE_NAME` USING fts4(
                    entryId,
                    projectId,
                    title,
                    content,
                    tags,
                    factKey,
                    notindexed=entryId,
                    notindexed=projectId,
                    tokenize=porter
                )
                """.trimIndent()
            )
            installFts4Triggers(db)
            db.execSQL(
                """
                INSERT INTO `$FTS4_TABLE_NAME` (`entryId`, `projectId`, `title`, `content`, `tags`, `factKey`)
                SELECT `id`, `projectId`, `title`, `content`, `tags`, `factKey`
                FROM `project_knowledge_entries`
                WHERE `id` NOT IN (SELECT `entryId` FROM `$FTS4_TABLE_NAME`)
                """.trimIndent()
            )
            true
        }.getOrDefault(false)

        fun supportsFts5(db: SupportSQLiteDatabase): Boolean =
            runCatching {
                db.query("PRAGMA compile_options").use { cursor ->
                    while (cursor.moveToNext()) {
                        val opt = cursor.getString(0) ?: continue
                        if (opt.equals("ENABLE_FTS5", ignoreCase = true) ||
                            opt.equals("SQLITE_ENABLE_FTS5", ignoreCase = true)
                        ) {
                            return@runCatching true
                        }
                    }
                    false
                }
            }.getOrDefault(false)

        fun isFts5Active(db: SupportSQLiteDatabase): Boolean =
            runCatching {
                db.query(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$FTS5_TABLE_NAME' LIMIT 1"
                ).use { it.moveToFirst() }
            }.getOrDefault(false)

        fun isFts4FallbackActive(db: SupportSQLiteDatabase): Boolean =
            runCatching {
                db.query(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$FTS4_TABLE_NAME' LIMIT 1"
                ).use { it.moveToFirst() }
            }.getOrDefault(false)

        private fun installFts5Triggers(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trg_knowledge_fts5_ai`
                AFTER INSERT ON `project_knowledge_entries`
                BEGIN
                    INSERT INTO `$FTS5_TABLE_NAME` (`entryId`, `projectId`, `title`, `content`, `tags`, `factKey`)
                    VALUES (new.`id`, new.`projectId`, new.`title`, new.`content`, new.`tags`, new.`factKey`);
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trg_knowledge_fts5_au`
                AFTER UPDATE ON `project_knowledge_entries`
                BEGIN
                    DELETE FROM `$FTS5_TABLE_NAME` WHERE `entryId` = old.`id`;
                    INSERT INTO `$FTS5_TABLE_NAME` (`entryId`, `projectId`, `title`, `content`, `tags`, `factKey`)
                    VALUES (new.`id`, new.`projectId`, new.`title`, new.`content`, new.`tags`, new.`factKey`);
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trg_knowledge_fts5_ad`
                AFTER DELETE ON `project_knowledge_entries`
                BEGIN
                    DELETE FROM `$FTS5_TABLE_NAME` WHERE `entryId` = old.`id`;
                END
                """.trimIndent()
            )
        }

        private fun installFts4Triggers(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trg_knowledge_fts4_ai`
                AFTER INSERT ON `project_knowledge_entries`
                BEGIN
                    INSERT INTO `$FTS4_TABLE_NAME` (`entryId`, `projectId`, `title`, `content`, `tags`, `factKey`)
                    VALUES (new.`id`, new.`projectId`, new.`title`, new.`content`, new.`tags`, new.`factKey`);
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trg_knowledge_fts4_au`
                AFTER UPDATE ON `project_knowledge_entries`
                BEGIN
                    DELETE FROM `$FTS4_TABLE_NAME` WHERE `entryId` = old.`id`;
                    INSERT INTO `$FTS4_TABLE_NAME` (`entryId`, `projectId`, `title`, `content`, `tags`, `factKey`)
                    VALUES (new.`id`, new.`projectId`, new.`title`, new.`content`, new.`tags`, new.`factKey`);
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trg_knowledge_fts4_ad`
                AFTER DELETE ON `project_knowledge_entries`
                BEGIN
                    DELETE FROM `$FTS4_TABLE_NAME` WHERE `entryId` = old.`id`;
                END
                """.trimIndent()
            )
        }

        val FTS_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                ensureKnowledgeFtsIndex(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                ensureKnowledgeFtsIndex(db)
            }
        }

        @Volatile
        private var INSTANCE: AgtDatabase? = null

        fun getInstance(context: Context): AgtDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AgtDatabase::class.java,
                    "agt_code.db",
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .addCallback(FTS_CALLBACK)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}


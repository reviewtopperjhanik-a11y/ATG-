package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.orchestrator.RepoMapEngine
import com.example.agent.orchestrator.RepositoryInspector
import com.example.agent.orchestrator.SymbolExtractor
import com.example.data.database.AgtDatabase
import com.example.data.intelligence.DevelopmentIntelligenceRepository
import com.example.data.project.ProjectRepository
import com.example.domain.model.FlightRecorderFilter
import com.example.domain.model.ProjectMemory
import com.example.domain.model.RepoLanguage
import com.example.domain.model.SymbolKind
import com.example.feature.workspace.FlightRecorderDialog
import com.example.ui.theme.AgtTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepoMapM3Test {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: AgtDatabase
    private lateinit var projectRepo: ProjectRepository
    private lateinit var intelligenceRepo: DevelopmentIntelligenceRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "workspaces").deleteRecursively()
        context.deleteDatabase("test_phase3_repomap.db")

        db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_phase3_repomap.db")
            .allowMainThreadQueries()
            .build()
        projectRepo = ProjectRepository(context, db.projectDao(), db.taskDao(), db.checkpointDao())
        intelligenceRepo = DevelopmentIntelligenceRepository(
            chatMessageDao = db.chatMessageDao(),
            taskStateSnapshotDao = db.taskStateSnapshotDao(),
            projectMemoryDao = db.projectMemoryDao(),
            flightRecorderDao = db.flightRecorderDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("test_phase3_repomap.db")
        File(context.filesDir, "workspaces").deleteRecursively()
    }

    @Test
    fun `symbol extractor parses kotlin, java, typescript, python, go, and rust while skipping comments`() {
        val kt = SymbolExtractor.extractSource(
            relativePath = "src/AuthService.kt",
            content = """
                package com.example.auth
                import com.example.domain.User
                // class FakeCommentedClass
                /* interface FakeBlockInterface */
                interface TokenSigner {
                    fun sign(userId: String): String
                }
                data class AuthResult(val ok: Boolean)
                object AuthConfig
                class AuthService : TokenSigner {
                    override fun sign(userId: String): String = userId
                }
                fun topLevelVerify(token: String): Boolean = true
            """.trimIndent(),
        )
        assertEquals(RepoLanguage.KOTLIN, kt.language)
        assertEquals("com.example.auth", kt.packageOrModule)
        assertTrue(kt.imports.contains("com.example.domain.User"))
        assertTrue(kt.symbols.any { it.name == "TokenSigner" && it.kind == SymbolKind.INTERFACE })
        assertTrue(kt.symbols.any { it.name == "AuthResult" && it.kind == SymbolKind.CLASS })
        assertTrue(kt.symbols.any { it.name == "AuthConfig" && it.kind == SymbolKind.OBJECT })
        assertTrue(kt.symbols.any { it.name == "sign" && it.kind == SymbolKind.METHOD })
        assertTrue(kt.symbols.any { it.name == "topLevelVerify" && it.kind == SymbolKind.FUNCTION })
        assertFalse(kt.symbols.any { it.name.startsWith("Fake") })

        val javaIdx = SymbolExtractor.extractSource(
            relativePath = "src/Ledger.java",
            content = """
                package com.bank;
                import java.util.List;
                public class Ledger {
                    public void recordEntry(String id) {}
                }
                public enum Status { OPEN, CLOSED }
            """.trimIndent(),
        )
        assertEquals(RepoLanguage.JAVA, javaIdx.language)
        assertTrue(javaIdx.symbols.any { it.name == "Ledger" && it.kind == SymbolKind.CLASS })
        assertTrue(javaIdx.symbols.any { it.name == "recordEntry" && it.kind == SymbolKind.METHOD })
        assertTrue(javaIdx.symbols.any { it.name == "Status" && it.kind == SymbolKind.ENUM })

        val tsIdx = SymbolExtractor.extractSource(
            relativePath = "src/api/client.ts",
            content = """
                import { Session } from '../auth/session';
                export interface ApiConfig { baseUrl: string }
                export type ApiId = string;
                export async function fetchProfile(id: ApiId): Promise<Session> { return {} as Session }
                export const formatUser = (name: string) => name.trim();
                export class ApiClient {
                    async getHealth(): Promise<boolean> { return true; }
                }
            """.trimIndent(),
        )
        assertEquals(RepoLanguage.TYPESCRIPT, tsIdx.language)
        assertTrue(tsIdx.imports.contains("../auth/session"))
        assertTrue(tsIdx.symbols.any { it.name == "ApiConfig" && it.kind == SymbolKind.INTERFACE })
        assertTrue(tsIdx.symbols.any { it.name == "ApiId" && it.kind == SymbolKind.TYPE_ALIAS })
        assertTrue(tsIdx.symbols.any { it.name == "fetchProfile" && it.kind == SymbolKind.FUNCTION })
        assertTrue(tsIdx.symbols.any { it.name == "formatUser" && it.kind == SymbolKind.FUNCTION })
        assertTrue(tsIdx.symbols.any { it.name == "ApiClient" && it.kind == SymbolKind.CLASS })
        assertTrue(tsIdx.symbols.any { it.name == "getHealth" && it.kind == SymbolKind.METHOD })

        val pyIdx = SymbolExtractor.extractSource(
            relativePath = "app/worker.py",
            content = """
                # def ignored_comment(): pass
                from app.models import JobSpec
                import asyncio
                class QueueWorker:
                    async def process_job(self, job: JobSpec) -> bool:
                        return True
                def bootstrap_worker():
                    return QueueWorker()
            """.trimIndent(),
        )
        assertEquals(RepoLanguage.PYTHON, pyIdx.language)
        assertTrue(pyIdx.imports.contains("app.models"))
        assertTrue(pyIdx.imports.contains("JobSpec"))
        assertTrue(pyIdx.symbols.any { it.name == "QueueWorker" && it.kind == SymbolKind.CLASS })
        assertTrue(pyIdx.symbols.any { it.name == "process_job" && it.kind == SymbolKind.METHOD })
        assertTrue(pyIdx.symbols.any { it.name == "bootstrap_worker" && it.kind == SymbolKind.FUNCTION })
        assertFalse(pyIdx.symbols.any { it.name == "ignored_comment" })

        val goIdx = SymbolExtractor.extractSource(
            relativePath = "cmd/server.go",
            content = """
                package main
                import (
                    "fmt"
                    "myapp/store"
                )
                type Handler interface {
                    Serve() error
                }
                type Server struct { port int }
                func (s *Server) Start() error { return nil }
                func NewServer(port int) *Server { return &Server{port: port} }
            """.trimIndent(),
        )
        assertEquals(RepoLanguage.GO, goIdx.language)
        assertTrue(goIdx.imports.contains("myapp/store"))
        assertTrue(goIdx.symbols.any { it.name == "Handler" && it.kind == SymbolKind.INTERFACE })
        assertTrue(goIdx.symbols.any { it.name == "Server" && it.kind == SymbolKind.STRUCT })
        assertTrue(goIdx.symbols.any { it.name == "Start" && it.kind == SymbolKind.METHOD })
        assertTrue(goIdx.symbols.any { it.name == "NewServer" && it.kind == SymbolKind.FUNCTION })

        val rsIdx = SymbolExtractor.extractSource(
            relativePath = "src/engine.rs",
            content = """
                use crate::storage::DiskStore;
                pub trait Runnable { fn run(&self); }
                pub struct TaskEngine { store: DiskStore }
                impl TaskEngine {
                    pub async fn dispatch(&self) {}
                }
                pub fn create_engine() {}
            """.trimIndent(),
        )
        assertEquals(RepoLanguage.RUST, rsIdx.language)
        assertTrue(rsIdx.symbols.any { it.name == "Runnable" && it.kind == SymbolKind.TRAIT })
        assertTrue(rsIdx.symbols.any { it.name == "TaskEngine" && it.kind == SymbolKind.STRUCT })
        assertTrue(rsIdx.symbols.any { it.name == "dispatch" && it.kind == SymbolKind.METHOD })
        assertTrue(rsIdx.symbols.any { it.name == "create_engine" && it.kind == SymbolKind.FUNCTION })
    }

    @Test
    fun `repo map ranks heavily referenced files higher, boosts recent edits, respects token budget, and caches incrementally`() {
        runBlocking {
            val proj = projectRepo.createProject("repomap-rank-proj").getOrThrow()
            val root = File(proj.workspacePath)
            File(root, "src/core").mkdirs()
            File(root, "src/features").mkdirs()
            File(root, "src/utils").mkdirs()

            // Central domain file imported and referenced by 3 feature files
            File(root, "src/core/ledger.ts").writeText(
                """
                export interface LedgerAccount { id: string; balance: number }
                export class LedgerService {
                    credit(acc: LedgerAccount, amount: number): LedgerAccount { return acc; }
                    debit(acc: LedgerAccount, amount: number): LedgerAccount { return acc; }
                }
                """.trimIndent()
            )
            File(root, "src/features/checkout.ts").writeText(
                """
                import { LedgerService, LedgerAccount } from '../core/ledger';
                export class CheckoutFlow {
                    execute(svc: LedgerService, acc: LedgerAccount) { return svc.debit(acc, 10); }
                }
                """.trimIndent()
            )
            File(root, "src/features/refund.ts").writeText(
                """
                import { LedgerService, LedgerAccount } from '../core/ledger';
                export class RefundFlow {
                    refund(svc: LedgerService, acc: LedgerAccount) { return svc.credit(acc, 10); }
                }
                """.trimIndent()
            )
            File(root, "src/features/audit.ts").writeText(
                """
                import { LedgerService } from '../core/ledger';
                export function inspectLedger(svc: LedgerService) { return svc != null; }
                """.trimIndent()
            )
            // Isolated unreferenced utility file with many dummy helpers
            File(root, "src/utils/unused_helpers.ts").writeText(
                buildString {
                    repeat(20) { i ->
                        appendLine("export function unusedHelperFunction$i(arg$i: string): string { return arg$i; }")
                    }
                }
            )
            File(root, "src/index.ts").writeText(
                """
                import { CheckoutFlow } from './features/checkout';
                export function main() { return new CheckoutFlow(); }
                """.trimIndent()
            )

            val rawInspection = RepositoryInspector.inspect(proj.id, proj.workspacePath).getOrThrow()

            // 1. First build -> cacheHit = false, persists into ProjectMemoryEntity
            val firstMap = intelligenceRepo.getOrBuildRepoMap(
                inspection = rawInspection,
                recentlyEditedPaths = listOf("src/features/refund.ts"),
                tokenBudget = 320,
            )
            assertFalse(firstMap.cacheHit)
            assertTrue(firstMap.estimatedTokens <= 320)
            assertEquals(6, firstMap.totalFilesIndexed)

            // LedgerService file has 3 incoming references and must outrank unused_helpers.ts
            val ledgerRankIdx = firstMap.rankedFiles.indexOfFirst { it.relativePath == "src/core/ledger.ts" }
            val unusedRankIdx = firstMap.rankedFiles.indexOfFirst { it.relativePath == "src/utils/unused_helpers.ts" }
            val refundRankIdx = firstMap.rankedFiles.indexOfFirst { it.relativePath == "src/features/refund.ts" }
            assertTrue(ledgerRankIdx >= 0 && unusedRankIdx > ledgerRankIdx)
            assertTrue(refundRankIdx >= 0 && unusedRankIdx > refundRankIdx)
            assertTrue(firstMap.rankedFiles[ledgerRankIdx].incomingReferences >= 3)
            assertTrue(firstMap.renderedMap.contains("LedgerService"))

            // ProjectMemory cached the RepoMap
            val savedMemory = intelligenceRepo.observeProjectMemory(proj.id).first()
            assertNotNull(savedMemory)
            assertEquals(firstMap.renderedMap, savedMemory!!.repoMapCache)
            assertTrue(savedMemory.repoMapUpdatedAtEpochMs > 0L)

            // ProjectInspection.toPromptContext emits Ranked Repo Map when populated
            val promptCtx = rawInspection.copy(repoMap = firstMap.renderedMap).toPromptContext()
            assertTrue(promptCtx.contains("Ranked Repo Map:"))
            assertFalse(promptCtx.contains("Project Files (bounded):"))

            // 2. Second untouched call -> cacheHit = true
            val secondMap = intelligenceRepo.getOrBuildRepoMap(
                inspection = rawInspection,
                recentlyEditedPaths = listOf("src/features/refund.ts"),
                tokenBudget = 320,
            )
            assertTrue(secondMap.cacheHit)
            assertEquals(firstMap.renderedMap, secondMap.renderedMap)

            // 3. Edit one file & invalidate -> cache invalidated, new symbol reflected
            File(root, "src/core/ledger.ts").appendText(
                "\nexport function freezeLedgerAccount(id: string): boolean { return true; }\n"
            )
            intelligenceRepo.invalidateRepoMap(proj.id, listOf("src/core/ledger.ts"))
            val staleMem = intelligenceRepo.observeProjectMemory(proj.id).first()
            assertEquals(0L, staleMem!!.repoMapUpdatedAtEpochMs)

            val rebuilt = intelligenceRepo.rebuildRepoMap(
                projectId = proj.id,
                projectRootPath = proj.workspacePath,
                recentlyEditedPaths = listOf("src/core/ledger.ts"),
                tokenBudget = 1600,
            ).getOrThrow()
            assertFalse(rebuilt.cacheHit)
            assertTrue(rebuilt.estimatedTokens <= 1600)
            assertTrue(rebuilt.renderedMap.contains("freezeLedgerAccount"))

            // 4. Tight token budget test -> never exceeds requested budget
            val tightMap = RepoMapEngine().buildFromInspection(
                inspection = rawInspection,
                tokenBudget = 110,
            )
            assertTrue(tightMap.estimatedTokens <= 110)
            assertTrue(tightMap.includedFilesCount < tightMap.totalFilesIndexed)
        }
    }

    @Test
    fun `flight recorder dialog expands cached repo map and triggers refresh`() {
        var refreshCount = 0
        val memory = ProjectMemory(
            projectId = "p-rm",
            stackLabel = "Kotlin",
            curatedRules = emptyList(),
            validationCommands = listOf("Gradle Test: gradle test"),
            repoMapCache = "# Repo Map (1/1 files, 2 symbols)\nsrc/Main.kt [entry]\n  L1: class AppController",
            repoMapUpdatedAtEpochMs = 1_700_000_000_000L,
            knownFixes = emptyList(),
            updatedAtEpochMs = 1_700_000_000_000L,
        )

        composeRule.setContent {
            AgtTheme {
                FlightRecorderDialog(
                    events = emptyList(),
                    totalEventCount = 0,
                    failureCount = 0,
                    selectedFilter = FlightRecorderFilter.ALL,
                    selectedTaskId = null,
                    taskSnapshots = emptyList(),
                    latestSnapshot = null,
                    projectMemory = memory,
                    onSelectFilter = {},
                    onSelectTask = {},
                    onInspectCheckpoint = {},
                    onDismiss = {},
                    onRefreshRepoMap = { refreshCount++ },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flight_recorder_repomap_toggle").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("flight_recorder_repomap_content").assertIsDisplayed()
        composeRule.onNodeWithText("# Repo Map", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("flight_recorder_repomap_refresh").performClick()
        composeRule.waitForIdle()
        assertEquals(1, refreshCount)
    }
}

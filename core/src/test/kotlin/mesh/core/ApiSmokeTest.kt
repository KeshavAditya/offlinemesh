package mesh.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

class ApiSmokeTest {
    @Test fun snapshotSerializesWithProtocolFieldNames() {
        val sched = object : Scheduler {
            override fun nowMs() = 0L
            override fun post(task: () -> Unit) = task()
            override fun schedule(delayMs: Long, task: () -> Unit) {}
        }
        val json = Json.encodeToString(MeshNode(java.io.File("."), sched, emptyList()).snapshot())
        assertTrue("\"self\"" in json && "\"debug\"" in json, json)
    }
}

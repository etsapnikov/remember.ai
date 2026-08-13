package ai.prinim.prinyal

import ai.prinim.prinyal.returns.ReturnDiag
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * След возврата должен различать три исхода, которые мы три захода путали:
 * аларм не пришёл, аларм пришёл но показа не было, показала страховка.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReturnDiagTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clean() {
        ReturnDiag.file(context).delete()
    }

    @Test
    fun `аларм не сработал — видно, что дальше плана ничего не было`() {
        ReturnDiag.log(context, "r1", ReturnDiag.SCHEDULED, mapOf("at" to 1_000L))

        val trace = ReturnDiag.recent(context).single()
        assertEquals(1_000L, trace.plannedAt!!.toEpochMilli())
        assertNull("аларма не было — не выдумывай его", trace.alarmAt)
        assertNull(trace.shownAt)
    }

    @Test
    fun `аларм был, а показа нет — это отдельный исход`() {
        ReturnDiag.log(context, "r2", ReturnDiag.SCHEDULED, mapOf("at" to 1_000L))
        ReturnDiag.log(context, "r2", ReturnDiag.ALARM)
        ReturnDiag.log(context, "r2", ReturnDiag.SKIPPED, mapOf("why" to "нет пункта"))

        val trace = ReturnDiag.recent(context).single()
        assertTrue(trace.alarmAt != null)
        assertNull(trace.shownAt)
        assertEquals("нет пункта", trace.note)
    }

    @Test
    fun `показ страховкой отличается от показа алармом`() {
        ReturnDiag.log(context, "r3", ReturnDiag.SCHEDULED, mapOf("at" to 1_000L))
        ReturnDiag.log(context, "r3", ReturnDiag.SHOWN, mapOf("catchup" to true))

        val viaCatchup = ReturnDiag.recent(context).single()
        assertTrue(viaCatchup.viaCatchup)

        ReturnDiag.file(context).delete()
        ReturnDiag.log(context, "r4", ReturnDiag.SCHEDULED, mapOf("at" to 1_000L))
        ReturnDiag.log(context, "r4", ReturnDiag.ALARM)
        ReturnDiag.log(context, "r4", ReturnDiag.SHOWN)

        assertFalse(ReturnDiag.recent(context).single().viaCatchup)
    }

    @Test
    fun `опоздание считается от плана до показа`() {
        val planned = System.currentTimeMillis() - 5 * 60_000
        ReturnDiag.log(context, "r5", ReturnDiag.SCHEDULED, mapOf("at" to planned))
        ReturnDiag.log(context, "r5", ReturnDiag.SHOWN)

        val late = ReturnDiag.recent(context).single().lateMs!!
        assertTrue("опоздание около пяти минут, а не $late", late in 4 * 60_000..6 * 60_000)
    }

    @Test
    fun `разные возвраты не сливаются в один`() {
        ReturnDiag.log(context, "a", ReturnDiag.SCHEDULED, mapOf("at" to 1_000L))
        ReturnDiag.log(context, "b", ReturnDiag.SCHEDULED, mapOf("at" to 2_000L))
        ReturnDiag.log(context, "a", ReturnDiag.ALARM)

        val traces = ReturnDiag.recent(context)
        assertEquals(2, traces.size)
        assertNull(traces.first { it.id == "b" }.alarmAt)
        assertTrue(traces.first { it.id == "a" }.alarmAt != null)
    }
}

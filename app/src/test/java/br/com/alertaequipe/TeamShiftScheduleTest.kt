package br.com.alertaequipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeamShiftScheduleTest {
    @Test fun twelveHourEndIsCalculatedFromStart() {
        assertEquals("07:00", TeamShiftSchedule.calculatedEnd("19:00"))
        assertEquals("19:00", TeamShiftSchedule.calculatedEnd("07:00"))
        assertEquals("00:30", TeamShiftSchedule.calculatedEnd("12:30"))
    }

    @Test fun invalidClockIsRejected() {
        assertNull(TeamShiftSchedule.calculatedEnd("25:00"))
        assertNull(TeamShiftSchedule.calculatedEnd("19"))
    }

    @Test fun mapParsingKeepsTwelveByThirtySixInvariant() {
        val schedule = TeamShiftSchedule.fromMap(mapOf("startTime" to "06:30", "endTime" to "00:00"))
        assertEquals("06:30", schedule.startTime)
        assertEquals("18:30", schedule.endTime)
        assertEquals(12, schedule.workHours)
        assertEquals(36, schedule.restHours)
    }
}

package com.lenne0815.karoocalendar.extension

import io.hammerhead.karooext.extension.KarooExtension

class KarooCalendarExtension : KarooExtension("karoo-calendar", "1.0") {
    override val types by lazy {
        listOf(
            CalendarDayDataType(extension),
        )
    }
}

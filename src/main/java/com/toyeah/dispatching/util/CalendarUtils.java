package com.toyeah.dispatching.util;

import java.util.Calendar;

public class CalendarUtils {

    /**
     * 今日零时
     * @return
     */
    public static Calendar todayZero() {
        Calendar instance = Calendar.getInstance();
        instance.set(Calendar.HOUR_OF_DAY, 0);
        instance.set(Calendar.MINUTE, 0);
        instance.set(Calendar.SECOND, 0);
        instance.set(Calendar.MILLISECOND, 0);
        return instance;
    }
}

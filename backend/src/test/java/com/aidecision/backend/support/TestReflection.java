package com.aidecision.backend.support;

import com.aidecision.backend.entity.ActivityLog;
import com.aidecision.backend.entity.RiskIngestRecord;

import java.lang.reflect.Field;

public final class TestReflection {

    private TestReflection() {}

    public static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        Class<?> cur = c;
        while (cur != null) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    public static void setRiskIngestRecordId(RiskIngestRecord r, long id) {
        setField(r, "id", id);
    }

    public static void setActivityLogId(ActivityLog a, long id) {
        setField(a, "id", id);
    }

    public static void setActivityLogCreatedAt(ActivityLog a, java.time.Instant t) {
        setField(a, "createdAt", t);
    }
}

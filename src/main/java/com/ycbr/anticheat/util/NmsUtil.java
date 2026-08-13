package com.ycbr.anticheat.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public final class NmsUtil {

    private static final float DEFAULT_WIDTH = 0.6F;
    private static final float DEFAULT_HEIGHT = 1.8F;

    private NmsUtil() {
    }

    public static int getPing(Player player) {
        try {
            Object handle = getHandle(player);
            Field field = handle.getClass().getField("ping");
            return field.getInt(handle);
        } catch (Exception e) {
            return -1;
        }
    }

    public static float getWidth(Entity entity) {
        try {
            Object handle = getHandle(entity);
            try {
                Field field = handle.getClass().getField("width");
                return field.getFloat(handle);
            } catch (NoSuchFieldException e) {
                Method method = handle.getClass().getMethod("getWidth");
                return ((Number) method.invoke(handle)).floatValue();
            }
        } catch (Exception e) {
            return DEFAULT_WIDTH;
        }
    }

    public static float getHeight(Entity entity) {
        try {
            Object handle = getHandle(entity);
            try {
                Field field = handle.getClass().getField("length");
                return field.getFloat(handle);
            } catch (NoSuchFieldException e) {
                Method method = handle.getClass().getMethod("getLength");
                return ((Number) method.invoke(handle)).floatValue();
            }
        } catch (Exception e) {
            return DEFAULT_HEIGHT;
        }
    }

    private static Object getHandle(Object craftObject) throws Exception {
        Method method = craftObject.getClass().getMethod("getHandle");
        return method.invoke(craftObject);
    }

    private static volatile Boolean occludingSupported = null;

    public static boolean isOccluding(org.bukkit.World world, int x, int y, int z) {
        try {
            Object handle = getHandle(world);
            Class<?> bpClass = Class.forName(handle.getClass().getPackage().getName() + ".BlockPosition");
            if (occludingSupported == null) {
                try {
                    handle.getClass().getMethod("isOccluding", bpClass);
                    occludingSupported = Boolean.TRUE;
                } catch (NoSuchMethodException e) {
                    occludingSupported = Boolean.FALSE;
                }
            }
            if (!occludingSupported) {
                return false;
            }
            Object pos = bpClass.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
            Method method = handle.getClass().getMethod("isOccluding", bpClass);
            return ((Boolean) method.invoke(handle, pos)).booleanValue();
        } catch (Exception e) {
            return false;
        }
    }
}
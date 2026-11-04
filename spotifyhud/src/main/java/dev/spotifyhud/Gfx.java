package dev.spotifyhud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Reflection wrappers around the few Minecraft rendering APIs that move around between versions
 * (matrix stack, textured blits, scissor, dynamic textures). Every call degrades gracefully: if
 * something is missing the HUD falls back to simpler drawing instead of crashing or failing to build.
 */
final class Gfx {
    private static final Logger LOG = LoggerFactory.getLogger("spotifyhud");

    // ---- matrix stack
    private static boolean poseTried, poseOk, rotateOk;
    private static Method mPose, mPush, mPop, mTranslate, mScale, mRotate;
    private static int translateArgs, scaleArgs;

    // ---- blit / scissor
    private static boolean blitTried, blitOk, scissorTried, scissorOk;
    private static Method mBlit, mScissorOn, mScissorOff;
    private static Object pipeline;

    // ---- textures
    private static boolean texTried, texOk;
    private static Class<?> nativeImageClass, dynTexClass;
    private static Method mTexManager, mRegister, mRelease, mRead;
    private static Object logged;

    private Gfx() {
    }

    // ============================================================ matrix stack

    private static Method find(Class<?> c, String name, Class<?>... params) {
        try {
            Method m = c.getMethod(name, params);
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
            }
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void initPose(GuiGraphicsExtractor g) {
        if (poseTried) return;
        poseTried = true;
        try {
            mPose = find(g.getClass(), "pose");
            Object st = mPose.invoke(g);
            Class<?> c = st.getClass();
            mPush = find(c, "pushMatrix");
            if (mPush == null) mPush = find(c, "pushPose");
            mPop = find(c, "popMatrix");
            if (mPop == null) mPop = find(c, "popPose");
            mTranslate = find(c, "translate", float.class, float.class);
            translateArgs = 2;
            if (mTranslate == null) {
                mTranslate = find(c, "translate", float.class, float.class, float.class);
                translateArgs = 3;
            }
            mScale = find(c, "scale", float.class, float.class);
            scaleArgs = 2;
            if (mScale == null) {
                mScale = find(c, "scale", float.class, float.class, float.class);
                scaleArgs = 3;
            }
            mRotate = find(c, "rotate", float.class);
            rotateOk = mRotate != null;
            poseOk = mPush != null && mPop != null && mTranslate != null && mScale != null;
        } catch (Throwable t) {
            poseOk = false;
        }
        if (!poseOk) LOG.warn("Matrix stack not available; text scaling and spinning disabled");
    }

    static boolean canTransform(GuiGraphicsExtractor g) {
        initPose(g);
        return poseOk;
    }

    static boolean canRotate(GuiGraphicsExtractor g) {
        initPose(g);
        return poseOk && rotateOk;
    }

    static void push(GuiGraphicsExtractor g) {
        try {
            mPush.invoke(mPose.invoke(g));
        } catch (Throwable t) {
            poseOk = false;
        }
    }

    static void pop(GuiGraphicsExtractor g) {
        try {
            mPop.invoke(mPose.invoke(g));
        } catch (Throwable t) {
            poseOk = false;
        }
    }

    static void translate(GuiGraphicsExtractor g, float x, float y) {
        try {
            Object st = mPose.invoke(g);
            if (translateArgs == 2) mTranslate.invoke(st, x, y);
            else mTranslate.invoke(st, x, y, 0f);
        } catch (Throwable t) {
            poseOk = false;
        }
    }

    static void scale(GuiGraphicsExtractor g, float sx, float sy) {
        try {
            Object st = mPose.invoke(g);
            if (scaleArgs == 2) mScale.invoke(st, sx, sy);
            else mScale.invoke(st, sx, sy, 1f);
        } catch (Throwable t) {
            poseOk = false;
        }
    }

    static void rotate(GuiGraphicsExtractor g, float radians) {
        try {
            mRotate.invoke(mPose.invoke(g), radians);
        } catch (Throwable t) {
            rotateOk = false;
        }
    }

    /** Text drawn at a scale (1 = normal). */
    static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color, boolean shadow, double scale) {
        if (scale <= 1.01 || !canTransform(g)) {
            g.text(font, s, x, y, color, shadow);
            return;
        }
        push(g);
        translate(g, x, y);
        scale(g, (float) scale, (float) scale);
        g.text(font, s, 0, 0, color, shadow);
        pop(g);
    }

    // ============================================================ blit / scissor

    private static Object num(Class<?> type, double v) {
        if (type == float.class) return (float) v;
        if (type == double.class) return v;
        if (type == long.class) return (long) v;
        return (int) Math.round(v);
    }

    /** Draws the whole texture (texW x texH pixels) stretched into a w x h area. Returns false if unavailable. */
    static boolean blit(GuiGraphicsExtractor g, Identifier id, int x, int y, int w, int h, int texW, int texH) {
        if (id == null) return false;
        try {
            if (!blitTried) {
                blitTried = true;
                try {
                    Class<?> rp = Class.forName("net.minecraft.client.renderer.RenderPipelines");
                    try {
                        pipeline = rp.getField("GUI_TEXTURED").get(null);
                    } catch (Throwable t) {
                        java.lang.reflect.Field f = rp.getDeclaredField("GUI_TEXTURED");
                        f.setAccessible(true);
                        pipeline = f.get(null);
                    }
                    for (Method m : g.getClass().getMethods()) {
                        if (!m.getName().equals("blit") || m.getParameterCount() != 12) continue;
                        Class<?>[] p = m.getParameterTypes();
                        if (p[0].isInstance(pipeline) && p[1].isAssignableFrom(Identifier.class)) {
                            mBlit = m;
                            break;
                        }
                    }
                    blitOk = mBlit != null && pipeline != null;
                } catch (Throwable t) {
                    blitOk = false;
                }
                if (!blitOk) LOG.warn("Textured blit not available; using pixel fallback for album art");
            }
            if (!blitOk) return false;
            Class<?>[] p = mBlit.getParameterTypes();
            mBlit.invoke(g, pipeline, id, num(p[2], x), num(p[3], y), num(p[4], 0), num(p[5], 0),
                    num(p[6], w), num(p[7], h), num(p[8], texW), num(p[9], texH), num(p[10], texW), num(p[11], texH));
            return true;
        } catch (Throwable t) {
            blitOk = false;
            LOG.warn("Blit failed, switching to fallback", t);
            return false;
        }
    }

    static boolean scissorOn(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        try {
            if (!scissorTried) {
                scissorTried = true;
                mScissorOn = find(g.getClass(), "enableScissor", int.class, int.class, int.class, int.class);
                mScissorOff = find(g.getClass(), "disableScissor");
                scissorOk = mScissorOn != null && mScissorOff != null;
            }
            if (!scissorOk) return false;
            mScissorOn.invoke(g, x1, y1, x2, y2);
            return true;
        } catch (Throwable t) {
            scissorOk = false;
            return false;
        }
    }

    static void scissorOff(GuiGraphicsExtractor g) {
        try {
            mScissorOff.invoke(g);
        } catch (Throwable t) {
            scissorOk = false;
        }
    }

    // ============================================================ textures (render thread only)

    private static Class<?> cls(String... names) {
        for (String n : names) {
            try {
                return Class.forName(n);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void initTex() {
        if (texTried) return;
        texTried = true;
        try {
            nativeImageClass = cls("com.mojang.blaze3d.platform.NativeImage", "net.minecraft.client.renderer.texture.NativeImage");
            dynTexClass = cls("net.minecraft.client.renderer.texture.DynamicTexture");
            mRead = find(nativeImageClass, "read", InputStream.class);
            Minecraft mc = Minecraft.getInstance();
            mTexManager = find(mc.getClass(), "getTextureManager");
            Object tm = mTexManager.invoke(mc);
            for (Method m : tm.getClass().getMethods()) {
                if (m.getParameterCount() == 2 && m.getName().equals("register")
                        && m.getParameterTypes()[0].isAssignableFrom(Identifier.class)) {
                    mRegister = m;
                }
                if (m.getParameterCount() == 1 && m.getName().equals("release")
                        && m.getParameterTypes()[0].isAssignableFrom(Identifier.class)) {
                    mRelease = m;
                }
            }
            texOk = nativeImageClass != null && dynTexClass != null && mRead != null && mRegister != null;
        } catch (Throwable t) {
            texOk = false;
        }
        if (!texOk) LOG.warn("Dynamic textures not available; using pixel fallback for album art");
    }

    /** Decodes PNG bytes into a GPU texture and registers it. Returns null on any failure. */
    static Identifier registerPng(byte[] png, String path) {
        initTex();
        if (!texOk || png == null) return null;
        try {
            Object img = mRead.invoke(null, new ByteArrayInputStream(png));
            Object tex = null;
            Constructor<?>[] ctors = dynTexClass.getConstructors();
            java.util.Arrays.sort(ctors, (a, b) -> Integer.compare(a.getParameterCount(), b.getParameterCount()));
            outer:
            for (Constructor<?> c : ctors) {
                Class<?>[] pt = c.getParameterTypes();
                Object[] args = new Object[pt.length];
                boolean hasImg = false;
                for (int i = 0; i < pt.length; i++) {
                    if (pt[i].isInstance(img)) {
                        args[i] = img;
                        hasImg = true;
                    } else if (pt[i] == Supplier.class) {
                        args[i] = (Supplier<String>) () -> "spotifyhud";
                    } else if (pt[i] == String.class) {
                        args[i] = "spotifyhud";
                    } else {
                        continue outer;
                    }
                }
                if (!hasImg) continue;
                try {
                    tex = c.newInstance(args);
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (tex == null) return null;
            Method up = find(tex.getClass(), "upload");
            if (up != null) {
                try {
                    up.invoke(tex);
                } catch (Throwable ignored) {
                }
            }
            Method filter = find(tex.getClass(), "setFilter", boolean.class, boolean.class);
            if (filter != null) {
                try {
                    filter.invoke(tex, true, false);
                } catch (Throwable ignored) {
                }
            }
            Identifier id = Identifier.fromNamespaceAndPath(SpotifyHudClient.MOD_ID, path);
            Object tm = mTexManager.invoke(Minecraft.getInstance());
            mRegister.invoke(tm, id, tex);
            return id;
        } catch (Throwable t) {
            if (logged == null) {
                logged = t;
                LOG.warn("Could not create texture", t);
            }
            return null;
        }
    }

    static void release(Identifier id) {
        if (id == null || mRelease == null) return;
        try {
            mRelease.invoke(mTexManager.invoke(Minecraft.getInstance()), id);
        } catch (Throwable ignored) {
        }
    }
}

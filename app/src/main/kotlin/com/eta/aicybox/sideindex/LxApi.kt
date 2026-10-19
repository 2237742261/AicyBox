package com.eta.aicybox.sideindex

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * libxposed 新版 API 适配层（1.32.0 起）。
 *
 * 为什么要这层：模块入口迁到新版 API 后 `targetApiVersion=102`，LSPosed 的
 * 「Xposed API 调用保护」会**禁止**再调用 `de.robv.android.xposed.*`（旧的
 * XposedHelpers / XposedBridge / XSharedPreferences 全部作废）。这里用一份很小的实现顶替它们：
 *
 *  - 反射调用走 [XposedInterface.getInvoker]，绕过隐藏 API 与访问检查（顶替 XposedHelpers.callXxx）
 *  - 类查找、字段读写用普通反射 —— SystemUI 是系统应用域，不受隐藏 API 限制（实测可用）
 *  - Hook 走 [XposedInterface.hook] 的拦截链，语义对齐旧的 before / after 回调
 *
 * ⚠️ 映射表按「类名#方法名/参数个数」缓存：同名同参数个数的重载只认第一个能装下实参的
 *   （本模块用到的都是唯一签名，够用；真要区分重载就把它拆成两个名字)。
 */
object LxApi {

    @Volatile private var api: XposedInterface? = null

    /** 缓存里的空值哨兵（ConcurrentHashMap 不收 null）。 */
    private val NONE = Any()

    private val classCache = ConcurrentHashMap<String, Any>()
    private val methodCache = ConcurrentHashMap<String, Any>()
    private val fieldCache = ConcurrentHashMap<String, Any>()

    fun attach(instance: XposedInterface) {
        api = instance
    }

    val attached: Boolean get() = api != null

    fun log(priority: Int, tag: String, msg: String, t: Throwable? = null) {
        val i = api ?: return
        try {
            if (t == null) i.log(priority, tag, msg) else i.log(priority, tag, msg, t)
        } catch (_: Throwable) {
        }
    }

    // ---------------- 类查找 ----------------

    fun findClass(name: String, cl: ClassLoader): Class<*>? {
        classCache[name]?.let { return if (it === NONE) null else it as Class<*> }
        val c = try {
            Class.forName(name, false, cl)
        } catch (_: Throwable) {
            null
        }
        classCache[name] = c ?: NONE
        return c
    }

    // ---------------- 方法调用 ----------------

    fun callMethod(target: Any?, name: String, vararg args: Any?): Any? {
        val cls = target?.javaClass ?: return null
        return invoke(resolve(cls, name, args), target, args)
    }

    fun callStaticMethod(cls: Class<*>?, name: String, vararg args: Any?): Any? {
        if (cls == null) return null
        return invoke(resolve(cls, name, args), null, args)
    }

    private fun invoke(m: Method?, obj: Any?, args: Array<out Any?>): Any? {
        if (m == null) throw IllegalStateException("找不到方法")
        val i = api
        return try {
            if (i != null) {
                i.getInvoker(m).invoke(obj, *args)
            } else {
                if (!m.isAccessible) m.isAccessible = true
                m.invoke(obj, *args)
            }
        } catch (t: Throwable) {
            throw IllegalStateException(
                "${m.declaringClass.simpleName}.${m.name} 调用失败：${t.javaClass.simpleName} ${t.message}",
                t
            )
        }
    }

    private fun resolve(cls: Class<*>, name: String, args: Array<out Any?>): Method? {
        val key = cls.name + '#' + name + '/' + args.size
        methodCache[key]?.let { return if (it === NONE) null else it as Method }
        var fallback: Method? = null
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name != name || m.parameterTypes.size != args.size) continue
                if (fits(m.parameterTypes, args)) {
                    methodCache[key] = m
                    return m
                }
                if (fallback == null) fallback = m
            }
            c = c.superclass
        }
        methodCache[key] = fallback ?: NONE
        return fallback
    }

    private fun fits(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        for (k in types.indices) {
            val a = args[k] ?: continue
            if (!box(types[k]).isAssignableFrom(a.javaClass)) return false
        }
        return true
    }

    private fun box(t: Class<*>): Class<*> = when (t) {
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> t
    }

    // ---------------- 字段读写 ----------------

    fun getObjectField(obj: Any?, name: String): Any? {
        val target = obj ?: return null
        return try {
            field(target.javaClass, name)?.get(target)
        } catch (_: Throwable) {
            null
        }
    }

    fun getIntField(obj: Any?, name: String): Int = (getObjectField(obj, name) as? Number)?.toInt() ?: 0

    fun getBooleanField(obj: Any?, name: String): Boolean = getObjectField(obj, name) as? Boolean ?: false

    private fun field(cls: Class<*>, name: String): Field? {
        val key = cls.name + '#' + name
        fieldCache[key]?.let { return if (it === NONE) null else it as Field }
        var c: Class<*>? = cls
        var f: Field? = null
        while (c != null && c != Any::class.java) {
            try {
                f = c.getDeclaredField(name)
                break
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        if (f != null && !f.isAccessible) {
            try {
                f.isAccessible = true
            } catch (_: Throwable) {
            }
        }
        fieldCache[key] = f ?: NONE
        return f
    }

    // ---------------- Hook ----------------

    /** 拦截链上的「可改返回值」包装，对齐旧 XC_MethodHook.MethodHookParam 的用法。 */
    class HookScope(val chain: XposedInterface.Chain) {
        private var handled = false
        private var value: Any? = null

        /** 设置这次调用的返回值 = 跳过原方法（对齐旧 param.result = …）。 */
        fun setResult(result: Any?) {
            value = result
            handled = true
        }

        internal fun skipOriginal(): Boolean = handled
        internal fun resultValue(): Any? = value
    }

    /** 原方法执行**之前**回调；[action] 里可以 setResult 跳过原方法。 */
    fun hookBefore(method: Method?, action: (HookScope) -> Unit): Boolean {
        val i = api ?: return false
        if (method == null) return false
        return try {
            i.hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val scope = HookScope(chain)
                    try {
                        action(scope)
                    } catch (_: Throwable) {
                    }
                    if (scope.skipOriginal()) scope.resultValue() else chain.proceed()
                }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 原方法执行**之后**回调（拿不到返回值，够本模块用）。 */
    fun hookAfter(method: Method?, action: (Any?) -> Unit): Boolean {
        val i = api ?: return false
        if (method == null) return false
        return try {
            i.hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        action(chain.thisObject)
                    } catch (_: Throwable) {
                    }
                    result
                }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 钩一个类的**所有**构造函数，构造完成后回调（对齐旧 XposedBridge.hookAllConstructors 的 afterHookedMethod）。 */
    fun hookConstructorsAfter(cls: Class<*>?, action: (Any) -> Unit): Int {
        val i = api ?: return 0
        if (cls == null) return 0
        var n = 0
        for (c in cls.declaredConstructors) {
            try {
                i.hook(c)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            chain.thisObject?.let { action(it) }
                        } catch (_: Throwable) {
                        }
                        result
                    }
                n++
            } catch (_: Throwable) {
            }
        }
        return n
    }
}

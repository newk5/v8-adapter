package io.alicorn.v8;

import com.eclipsesource.v8.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Timestamp;

import java.util.*;

/**
 * Utilities for adapting Java classes and objects into a V8 runtime.
 *
 * @author Brandon Sanders [brandon@alicorn.io]
 */
public final class V8JavaAdapter {

    // Mapping of V8 instances to their associated caches.
    private static final Map<V8, V8JavaCache> runtimeToCacheMap = new WeakHashMap<V8, V8JavaCache>();
    private static Map<V8, LinkedHashSet> temporaryObjects = new HashMap<V8, LinkedHashSet>();

    public static void nullTemporaryGlobals(V8 v8) {
        LinkedHashSet<String> set = temporaryObjects.get(v8);
        if (set != null) {
            for (String var : set) {
                v8.executeVoidScript(var + "=null;");
            }

            set.clear();
            temporaryObjects.put(v8, set);
        }
    }

    /**
     * Returns the {@link V8JavaCache} associated with a given runtime.
     *
     * If a cache for that runtime does not exist, one will be created when this
     * method is invoked.
     *
     * @param v8 V8 runtime to retrieve the cache for.
     *
     * @return The {@link V8JavaCache} associated with the given runtime.
     */
    public static V8JavaCache getCacheForRuntime(V8 v8) {
        if (runtimeToCacheMap.containsKey(v8)) {
            return runtimeToCacheMap.get(v8);
        } else {
            V8JavaCache cache = new V8JavaCache();
            runtimeToCacheMap.put(v8, cache);
            return cache;
        }
    }

    /**
     * Injects an existing Java object into V8 as a variable.
     *
     * If the passed object represents a primitive array (e.g., String[],
     * Object[], int[]), the array will be unwrapped and injected into the V8
     * context as an ArrayList. Any modifications made to the injected list will
     * not be passed back up to the Java runtime.
     *
     * This method will immediately invoke
     * {@link #injectClass(String, Class, V8Object)} before injecting the
     * object, causing the object's class to be automatically injected into the
     * V8 Object if it wasn't already.
     *
     * <b>NOTE: </b> If you wish to use an interceptor for the class of an
     * injected object, you must explicitly invoke
     * {@link #injectClass(Class, V8JavaClassInterceptor, V8Object)} or
     * {@link #injectClass(String, Class, V8JavaClassInterceptor, V8Object)}.
     * This method will
     * <b>NOT</b> specify an interceptor automatically for the injected object.
     *
     * @param name Name of the variable to assign the Java object to. If this
     * value is null, a UUID will be automatically generated and used as the
     * name of the variable.
     * @param object Java object to inject.
     * @param rootObject {@link V8Object} to inject the Java object into.
     *
     * @return String identifier of the injected object.
     */
    public static String injectObject(String name, Object object, V8Object rootObject) {

        // Determine cache to use.
        V8JavaCache cache = getCacheForRuntime(rootObject.getRuntime());

        //TODO: Add special handlers for N-dimensional and primitive arrays.
        //TODO: This should inject arrays as JS arrays, not lists. Meh.
        //TODO: This will bypass interceptors in some cases.
        //TODO: This is terrible.
        if (object.getClass().isArray()) {
            Object[] rawArray = (Object[]) object;
            List<Object> injectedArray = new ArrayList<Object>(rawArray.length);
            for (Object obj : rawArray) {
                injectedArray.add(obj);
            }
            return injectObject(name, injectedArray, rootObject);
        } else {
            injectClass("".equals(object.getClass().getSimpleName())
                    ? object.getClass().getName().replaceAll("\\.+", "_")
                    : object.getClass().getSimpleName(),
                    object.getClass(),
                    rootObject);
        }

        if (name == null) {
            name = "TEMP" + UUID.randomUUID().toString().replaceAll("-", "");
            V8 v8 = V8JavaObjectUtils.getRuntimeSarcastically(rootObject);
            LinkedHashSet set = temporaryObjects.get(v8);
            if (set == null) {
                LinkedHashSet<String> objectNames = new LinkedHashSet<String>();
                objectNames.add(name);
                temporaryObjects.put(v8, objectNames);
            } else {
                set.add(name);
            }
        }

        //Build an empty object instance.
        V8JavaClassProxy proxy = cache.cachedV8JavaClasses.get(object.getClass());
        StringBuilder script = new StringBuilder();
        script.append("var ").append(name).append(" = new function() {");

        // Attach interceptor.
        if (proxy.getInterceptor() != null) {
            script.append(proxy.getInterceptor().getConstructorScriptBody());
        }

        script.append("\n}; ").append(name).append(";");

        V8Object other = V8JavaObjectUtils.getRuntimeSarcastically(rootObject).executeObjectScript(script.toString());
        String id = proxy.attachJavaObjectToJsObject(object, other);
        other.release();
        return id;
    }

    /**
     * Injects a Java class into a V8 object as a prototype.
     *
     * The injected "class" will be equivalent to a Java Script prototype with a
     * name identical to the one specified when invoking this function. For
     * example, the java class {@code com.foo.Bar} could be new'd from the Java
     * Script context by invoking {@code new Bar()} if {@code "Bar"} was passed
     * as the name use when injecting the class.
     *
     * @param name Name to use when injecting the class into the V8 object.
     * @param classy Java class to inject.
     * @param interceptor {@link V8JavaClassInterceptor} to use with this class.
     * Pass null if no interceptor is desired.
     * @param rootObject {@link V8Object} to inject the Java class into.
     */
    public static void injectClass(String name, Class<?> classy, V8JavaClassInterceptor interceptor, V8Object rootObject) {
        //Calculate V8-friendly full class names.
        String v8FriendlyClassname = classy.getName().replaceAll("\\.+", "_");

        // Determine cache to use.
        V8JavaCache cache = getCacheForRuntime(rootObject.getRuntime());

        //Register the class proxy.
        V8JavaClassProxy proxy;
        if (cache.cachedV8JavaClasses.containsKey(classy)) {
            proxy = cache.cachedV8JavaClasses.get(classy);
        } else {
            proxy = new V8JavaClassProxy(classy, interceptor, cache);
            cache.cachedV8JavaClasses.put(classy, proxy);
        }

        //Check if the root object already has a constructor.
        //TODO: Is this faster or slower than checking if a specific V8Value is "undefined"?
        if (!Arrays.asList(rootObject.getKeys()).contains("v8ConstructJavaClass" + v8FriendlyClassname)) {
            rootObject.registerJavaMethod(proxy, "v8ConstructJavaClass" + v8FriendlyClassname);

            //Build up the constructor script.
            StringBuilder script = new StringBuilder();
            script.append("this.").append(name).append(" = function() {");
            script.append("v8ConstructJavaClass").append(v8FriendlyClassname).append(".apply(this, arguments);");

            // Attach interceptor.
            if (proxy.getInterceptor() != null) {
                script.append(proxy.getInterceptor().getConstructorScriptBody());
            }

            script.append("\n};");

            //Evaluate the script to create a new constructor function.
            V8JavaObjectUtils.getRuntimeSarcastically(rootObject).executeVoidScript(script.toString());

            //Build up static methods if needed.
            if (proxy.getInterceptor() == null) {
                V8Object constructorFunction = (V8Object) rootObject.get(name);
                for (V8JavaStaticMethodProxy method : proxy.getStaticMethods()) {
                    constructorFunction.registerJavaMethod(method, method.getMethodName());
                }
                /*injectFinalStaticProperties(classy, constructorFunction);

                for (Class c : classy.getDeclaredClasses()) {
                    if (!c.getSimpleName().equalsIgnoreCase(name)) {
                        V8Object innerObj = new V8Object(constructorFunction.getRuntime());
                        injectFinalStaticProperties(c, innerObj);
                        constructorFunction.add(c.getSimpleName(), innerObj);
                        innerObj.release();
                    }
                }
                 */
                //Clean up after ourselves.
                constructorFunction.release();

            }
        }
    }

    public static void injectEnumOrdinals(Class c, V8 v8) {
        StringBuilder sb = new StringBuilder();
        if (c.isEnum()) {
            for (int i = 0; i < c.getEnumConstants().length; i++) {
                Object obj = c.getEnumConstants()[i];
                sb.append(obj.toString()).append(":").append(i);
                if (!((c.getEnumConstants().length - 1) == i)) {
                    sb.append(",");
                }
            }
            if (c.getEnumConstants().length > 0) {
                v8.executeVoidScript("var " + c.getSimpleName() + "= {" + sb.toString() + "};");
            }
        }
    }

    public static void injectEnumOrdinals(String name, Class c, V8 v8) {
        StringBuilder sb = new StringBuilder();
        if (c.isEnum()) {
            for (int i = 0; i < c.getEnumConstants().length; i++) {
                Object obj = c.getEnumConstants()[i];
                sb.append(obj.toString()).append(":").append(i);
                if (!((c.getEnumConstants().length - 1) == i)) {
                    sb.append(",");
                }
            }
            if (c.getEnumConstants().length > 0) {
                v8.executeVoidScript("var " + name + "= {" + sb.toString() + "};");
            }
        }
    }

    public static void injectEnumOrdinalsIntoGlobalObj(Class c, String globalObject, String propertyName, V8 v8) {

        boolean createInnnerObj = propertyName != null;
        if (c.isEnum()) {
            if (createInnnerObj) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < c.getEnumConstants().length; i++) {
                    Object obj = c.getEnumConstants()[i];
                    sb.append(obj.toString()).append(":").append(i);
                    if (!((c.getEnumConstants().length - 1) == i)) {
                        sb.append(",");
                    }
                }
                if (c.getEnumConstants().length > 0) {
                    v8.executeVoidScript(globalObject + "." + propertyName + "={ " + sb.toString() + " };");
                }
            } else {
                for (int i = 0; i < c.getEnumConstants().length; i++) {
                    Object obj = c.getEnumConstants()[i];
                    StringBuilder sb = new StringBuilder();

                    sb.append(globalObject).append(".").append(obj.toString()).append("=").append(i).append(";");
                    v8.executeVoidScript(sb.toString());

                }
            }
        }
    }

    private static void injectFinalStaticProperties(Class<?> classy, V8Object jsObject) {
        //register public properties
        Field[] fields = classy.getDeclaredFields();

        for (Field f : fields) {
            if (Modifier.isPublic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())) {
                // Define property on JS object.
                V8Object object = V8JavaObjectUtils.getRuntimeSarcastically(jsObject).getObject("Object");
                try {
                    if (f.getType() == int.class || f.getType() == Integer.class) {
                        object.add("value", f.getInt(null));
                    } else if (f.getType() == double.class || f.getType() == Double.class) {
                        object.add("value", f.getDouble(null));
                    } else if (f.getType() == boolean.class || f.getType() == Boolean.class) {
                        object.add("value", f.getBoolean(null));
                    } else if (f.getType() == float.class || f.getType() == Float.class) {
                        object.add("value", f.getFloat(null));
                    } else if (f.getType() == String.class) {
                        object.add("value", f.get(null).toString());
                    }
                    object.add("writable", true);
                    object.add("enumerable", true);
                    object.add("configurable", true);
                } catch (IllegalArgumentException illegalArgumentException) {
                } catch (IllegalAccessException illegalAccessException) {
                }

                // Create a new JS object.
                V8Object ret = (V8Object) object.executeJSFunction("defineProperty", jsObject, f.getName(), object);
                ret.release();
                object.release();
            }
        }
    }

    public static void injectClass(Class<?> classy, V8JavaClassInterceptor interceptor, V8Object object) {
        injectClass(classy.getSimpleName(), classy, interceptor, object);
    }

    public static void injectClass(String name, Class<?> classy, V8Object object) {
        injectClass(name, classy, null, object);
    }

    public static void injectClass(Class<?> classy, V8Object object) {
        injectClass(classy.getSimpleName(), classy, null, object);
    }
}

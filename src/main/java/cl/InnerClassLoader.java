package cl;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.Transformer;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.SuperMethodCall;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

/**
 * bytebuddytest
 * <p>
 * Created by Erik Håkansson on 2016-07-05.
 * Copyright 2016
 */
public class InnerClassLoader extends ClassLoader {

    private static Map<ClassFileLocator, TypePool> typePools = new HashMap<>();
    TypePool typePool = TypePool.Default.of(this);
    private Map<String, Class<?>> loadedClasses = new HashMap<>();
    private Map<String, byte[]> byteCache = new HashMap<>();

    public InnerClassLoader(URL url) throws Exception {
        File directory = new File(url.toURI());
        addDirectory(url, directory);
    }

    private static boolean hasPackagePrivateMethod(MethodDescription methodDescription) {
        return methodDescription.isPackagePrivate();
    }

    private static boolean hasPackagePrivateOrProtectedField(FieldDescription fieldDescription) {
        return fieldDescription.isPackagePrivate() || fieldDescription.isProtected();
    }

    private static boolean isPackagePrivateClass(TypeDescription typeDescription) {
        return (typeDescription.isPackagePrivate() && !typeDescription.isAnnotation());
    }

    private static ElementMatcher<FieldDescription> fieldMatchers() {
        return ElementMatchers.isPackagePrivate().or(ElementMatchers.isProtected())
            .and(ElementMatchers.not(ElementMatchers.isAnnotatedWith(ElementMatchers.named("javax.inject.Inject"))));
    }

    private static boolean shouldProxyMethod(MethodDescription.InDefinedShape inDefinedShape) {
        return inDefinedShape.isPackagePrivate();
    }

    private static boolean shouldProxyClass(TypeDescription typeDescription) {
        return (typeDescription.isPackagePrivate() && !typeDescription.isAnnotation());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (loadedClasses.containsKey(name)) {
            return loadedClasses.get(name);
        }
        if (!byteCache.containsKey(name) || name.startsWith("java.") || name.startsWith("net.bytebuddy") ||
            name.startsWith("cl.")) {
            if (name.equals("com.google.gson.Gson")) {
                throw new ClassNotFoundException(
                    "Fake exception since I couldn't find bytecode for GSON. Please run mvn clean install to copy .class files");
            }
            return super.loadClass(name, resolve);
        } else {

            //noinspection SuspiciousMethodCalls
            ClassFileLocator classFileLocator = ClassFileLocator.ForClassLoader.of(this);
            if (!typePools.containsKey(classFileLocator)) {
                typePools.put(classFileLocator, TypePool.Default.of(classFileLocator));
            }

            //noinspection SuspiciousMethodCalls
            TypeDescription typeDescription = typePools.get(classFileLocator).describe(name).resolve();
            if (typeDescription.isAbstract()) {
                Class<?> loaded = null;
                try {
                    InputStream inputStream = getResource(name.replace('.', '/') + ".class").openStream();
                    byte[] classBytes = IOUtils.toByteArray(inputStream);
                    loaded = defineClass(name, classBytes, 0, classBytes.length, getClass().getProtectionDomain());
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (loaded == null) {
                    loaded = super.loadClass(name, resolve);
                }
                loadedClasses.put(name, loaded);
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }


            boolean shouldProxyClass = isPackagePrivateClass(typeDescription);
            boolean shouldProxyMethod = false;
            boolean shouldProxyField = false;
            for (MethodDescription methodDescription : typeDescription.getDeclaredMethods()) {
                shouldProxyMethod = hasPackagePrivateMethod(methodDescription);
                if (shouldProxyMethod) {
                    break;
                }
            }
            if (!shouldProxyMethod) {
                for (FieldDescription fieldDescription : typeDescription.getDeclaredFields()) {
                    shouldProxyField = hasPackagePrivateOrProtectedField(fieldDescription);
                    if (shouldProxyField) {
                        break;
                    }
                }
            }

            if (!shouldProxyMethod && !shouldProxyClass && !shouldProxyField) {
                Class<?> loaded = null;
                try {
                    InputStream inputStream = getResource(name.replace('.', '/') + ".class").openStream();
                    byte[] classBytes = IOUtils.toByteArray(inputStream);
                    loaded = defineClass(name, classBytes, 0, classBytes.length, getClass().getProtectionDomain());
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (loaded == null) {
                    loaded = super.loadClass(name, resolve);
                }
                loadedClasses.put(name, loaded);
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }

            DynamicType.Builder<?> builder = null;
            try {
                builder = new ByteBuddy().with(TypeValidation.DISABLED).rebase(typeDescription, classFileLocator);
            } catch (IllegalStateException e) {
                if (e.getMessage().startsWith("Cannot resolve type description for")) {
                    //Attempt to work around the fact that Java Reflection API cannot handle missing classes; by simply not
                    // proxying:
                    return null;
                }
                //rethrow:
                throw e;
            }
            DynamicType.Unloaded unloaded;
            if (shouldProxyClass) {
                if (!shouldProxyField && !shouldProxyMethod) {
                    unloaded = builder.modifiers(Visibility.PUBLIC).make();
                } else {
                    unloaded = builder.modifiers(Visibility.PUBLIC).method(
                        ElementMatchers.isPackagePrivate().or(ElementMatchers.isProtected())
                            .and(ElementMatchers.not(ElementMatchers.isAbstract().or(ElementMatchers.isDefaultMethod()))))
                        .intercept(MethodDelegation.to(PackagePrivateInterceptor.class).andThen(SuperMethodCall.INSTANCE))
                        .transform(Transformer.ForMethod.withModifiers(Visibility.PUBLIC)).field(fieldMatchers())
                        .transform(Transformer.ForField.withModifiers(Visibility.PUBLIC)).make();
                }
            } else {
                //Only package-private methods should be proxied.
                unloaded = builder.method(ElementMatchers.isPackagePrivate().or(ElementMatchers.isProtected())
                    .and(ElementMatchers.not(ElementMatchers.isAbstract().or(ElementMatchers.isDefaultMethod()))))
                    .intercept(MethodDelegation.to(PackagePrivateInterceptor.class).andThen(SuperMethodCall.INSTANCE))
                    .transform(Transformer.ForMethod.withModifiers(Visibility.PUBLIC)).field(fieldMatchers())
                    .transform(Transformer.ForField.withModifiers(Visibility.PUBLIC)).make();
            }

            Class<?> loaded = unloaded
                .load(this, ClassLoadingStrategy.Default.INJECTION.withProtectionDomain(this.getClass().getProtectionDomain()))
                .getLoaded();


            if (resolve) {
                resolveClass(loaded);
            }
            loadedClasses.put(name, loaded);
            return loaded;
        }

    }

    public void addDirectory(URL url, File directory) throws Exception {
        if (url.getPath().endsWith(".jar")) {
            JarInputStream jarInputStream = new JarInputStream(url.openStream());
            JarEntry jarEntry;
            while ((jarEntry = jarInputStream.getNextJarEntry()) != null) {
                if (jarEntry.getName().endsWith(".class")) {
                    JarFile jarFile = new JarFile(new File(url.toURI()));
                    InputStream is = jarFile.getInputStream(jarFile.getEntry(jarEntry.getName()));
                    int len;
                    byte[] b = new byte[2048];
                    ByteArrayOutputStream entryOutputStream = new ByteArrayOutputStream();

                    while ((len = is.read(b)) > 0) {
                        entryOutputStream.write(b, 0, len);
                    }

                    is.close();
                    byteCache.put(resourceToClassName(jarEntry.getName()), entryOutputStream.toByteArray());
                    entryOutputStream.close();
                }
            }
            return;
        } else if (!directory.isDirectory()) {
            throw new IllegalStateException("Not a directory: " + directory);
        }
        File[] files = directory.listFiles();
        if (files == null) {
            throw new IllegalStateException("No files found in " + directory);
        }
        for (File file : files) {
            if (file.isDirectory()) {
                addDirectory(url, file);
            } else {
                try {
                    String relativeName = url.toURI().relativize(file.toURI()).getPath();
                    FileInputStream fileInputStream = new FileInputStream(file);
                    addClassIfClass(fileInputStream, relativeName);
                    fileInputStream.close();
                } catch (MalformedURLException | URISyntaxException | FileNotFoundException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private void addClassIfClass(InputStream inputStream, String relativePath) throws IOException {
        if (relativePath.endsWith(".class")) {
            int len;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[2048];

            while ((len = inputStream.read(b)) > 0) {
                out.write(b, 0, len);
            }
            out.close();
            byte[] classBytes = out.toByteArray();
            String className = resourceToClassName(relativePath);
            byteCache.put(className, classBytes);
        }
    }

    private String resourceToClassName(String slashed) {
        return slashed.substring(0, slashed.lastIndexOf(".class")).replace("/", ".");
    }

    public static class CacheTypePool extends TypePool.Default {

        public CacheTypePool(CacheProvider cacheProvider, ClassFileLocator classFileLocator, ReaderMode readerMode) {
            super(cacheProvider, classFileLocator, readerMode);
        }

        public CacheTypePool(CacheProvider cacheProvider, ClassFileLocator classFileLocator, ReaderMode readerMode,
            TypePool parentPool) {
            super(cacheProvider, classFileLocator, readerMode, parentPool);
        }

        public ClassFileLocator getClassFileLocator() {
            return classFileLocator;
        }
    }
}

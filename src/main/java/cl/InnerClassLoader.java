package cl;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.MethodTransformer;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

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

    private Map<String, Class<?>> loadedClasses = new HashMap<>();

    public InnerClassLoader(URL url) throws Exception {
        File directory = new File(url.toURI());
        addDirectory(url, directory);
    }

    TypePool typePool = TypePool.Default.of(this);

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (loadedClasses.containsKey(name)) {
            return loadedClasses.get(name);
        }
        if (!byteCache.containsKey(name) || name.startsWith("java.") || name.startsWith("net.bytebuddy") || name.startsWith("cl.")) {
            if (name.equals("com.google.gson.Gson")) {
                throw new ClassNotFoundException("Fake exception since I couldn't find bytecode for GSON. Please run mvn clean install to copy .class files");
            }
            return super.loadClass(name, resolve);
        } else {
            TypeDescription typeDescription = typePool.describe(name).resolve();
            boolean shouldProxyClass = shouldProxyClass(typeDescription);

            Class<?> loaded;
            if (shouldProxyClass) {
                DynamicType.Unloaded unloaded = new ByteBuddy().with(TypeValidation.DISABLED).rebase(typeDescription,
                    ClassFileLocator.Simple.of(name, byteCache.get(name),
                        ClassFileLocator.ForClassLoader.of(this))).method(
                    ElementMatchers.not(ElementMatchers.isPrivate()).and(
                        ElementMatchers.not(ElementMatchers.isAbstract()))).intercept(
                    MethodDelegation.to(PackagePrivateInterceptor.class)).transform(
                    MethodTransformer.Simple.withModifiers(Visibility.PUBLIC)).make();

                String packageName = unloaded.getTypeDescription().getPackage().getName();
                if (getPackage(packageName) == null) {
                    definePackage(packageName, null, null, null, null, null, null, null);
                }

                loaded = unloaded.load(this,
                    ClassLoadingStrategy.Default.INJECTION.withProtectionDomain(
                        this.getClass().getProtectionDomain())).getLoaded();
            } else {
                loaded = defineClass(name, byteCache.get(name), 0, byteCache.get(name).length, this.getClass().getProtectionDomain());
            }



            if (resolve) {
                resolveClass(loaded);
            }
            loadedClasses.put(name, loaded);
            return loaded;
        }

    }

    private static boolean shouldProxyMethod(MethodDescription.InDefinedShape inDefinedShape) {
        return inDefinedShape.isPackagePrivate();
    }

    private static boolean shouldProxyClass(TypeDescription typeDescription) {
        return (typeDescription.isPackagePrivate() && !typeDescription.isAnnotation());
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

    private Map<String, byte[]> byteCache = new HashMap<>();

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
}

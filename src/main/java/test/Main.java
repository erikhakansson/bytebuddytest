package test;

import cl.InnerClassLoader;

/**
 * bytebuddytest
 * <p>
 * Created by Erik Håkansson on 2016-07-05.
 * Copyright 2016
 */
public class Main {

    public static void main(String[] args) throws Exception {
        InnerClassLoader innerClassLoader = new InnerClassLoader(Main.class.getProtectionDomain().getCodeSource().getLocation());
        innerClassLoader.loadClass("test.Stuff").newInstance();
    }

}

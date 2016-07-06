package test;

import com.google.gson.Gson;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * bytebuddytest
 * <p>
 * Created by Erik Håkansson on 2016-07-05.
 * Copyright 2016
 */
public class Stuff {

    public Stuff() {
        Gson gson = new Gson();
        TestThing testThing = new TestThing();
        testThing.setTest("test");
        testThing.setSet(new HashSet<>());
        Map<String, String> stuff = new HashMap<>();
        stuff.put("test", "1");
        testThing.setMapType(stuff);
        String json = gson.toJson(testThing);
        gson.fromJson(json, TestThing.class);
    }


}

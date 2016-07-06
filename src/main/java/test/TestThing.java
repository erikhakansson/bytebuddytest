package test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * bytebuddytest
 * <p>
 * Created by Erik Håkansson on 2016-07-05.
 * Copyright 2016
 */
public class TestThing {
    public String test;
    public Set<String> set;
    Map<String, String> mapType = new HashMap<>();

    public String getTest() {
        return test;
    }

    public void setTest(String test) {
        this.test = test;
    }

    public Set<String> getSet() {
        return set;
    }

    public void setSet(Set<String> set) {
        this.set = set;
    }

    public Map<String, String> getMapType() {
        return mapType;
    }

    public void setMapType(Map<String, String> mapType) {
        this.mapType = mapType;
    }
}

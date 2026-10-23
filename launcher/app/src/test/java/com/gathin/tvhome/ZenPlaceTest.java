package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for weather_city: choosing among the places Open-Meteo's geocoding returns. */
public class ZenPlaceTest {

    private static JSONObject place(String name, String admin1, String country, String code, double lat) throws Exception {
        return new JSONObject().put("name", name).put("admin1", admin1).put("country", country)
                .put("country_code", code).put("latitude", lat).put("longitude", lat + 1);
    }

    /** Shaped like the real answer for "Resistencia", in that order; the coordinates are made up. */
    private static JSONObject answer() throws Exception {
        return new JSONObject().put("results", new JSONArray()
                .put(place("Ciudad de Resistencia", "Provincia del Chaco", "Argentina", "AR", 1))
                .put(place("Puerto Resistencia", "Departamento de Loreto", "Perú", "PE", 2))
                .put(place("Resistencia", "Departamento de Tarija", "Bolivia", "BO", 3)));
    }

    @Test
    public void theFirstCandidateWithoutHints() throws Exception {
        assertEquals(1, ZenPlace.pick("Resistencia", answer()).latitude, 0);
    }

    @Test
    public void laterPartsNarrowItDown() throws Exception {
        assertEquals(1, ZenPlace.pick("Resistencia, Chaco, Argentina", answer()).latitude, 0);
        assertEquals(3, ZenPlace.pick("Resistencia, Bolivia", answer()).latitude, 0);
        assertEquals(2, ZenPlace.pick("Resistencia, Peru", answer()).latitude, 0);   // without the accent
        assertEquals(3, ZenPlace.pick("Resistencia, BO", answer()).latitude, 0);
        assertEquals("Resistencia, Bolivia", ZenPlace.pick("Resistencia, Bolivia", answer()).query);
    }

    @Test
    public void nothingMatchesIsNull() throws Exception {
        assertNull(ZenPlace.pick("Resistencia, Chile", answer()));
        assertNull(ZenPlace.pick("Nowhere", new JSONObject("{\"generationtime_ms\":0.5}")));
    }

    @Test
    public void theSearchIsForTheFirstPart() {
        String url = ZenPlace.url("São Paulo, Brasil", "pt");
        assertTrue(url, url.startsWith("https://geocoding-api.open-meteo.com/v1/search?name=S%C3%A3o+Paulo&"));
        assertTrue(url, url.endsWith("&language=pt"));
        assertTrue(ZenPlace.url("Resistencia", "").endsWith("&language=en"));
    }

    @Test
    public void partsAreTrimmedAndBlanksDropped() {
        assertArrayEquals(new String[]{"Resistencia", "Chaco"}, ZenPlace.parts(" Resistencia , , Chaco ,"));
        assertArrayEquals(new String[]{""}, ZenPlace.parts(" , "));
    }
}

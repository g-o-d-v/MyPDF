package com.nless.pdf_search_engine.cache;

import com.nless.pdf_search_engine.ocr.OcrPageResult;

import java.util.LinkedHashMap;
import java.util.Map;

public class OcrMemoryCache {

    private final int maxEntries;

    private final LinkedHashMap<String, OcrPageResult> cache;

    public OcrMemoryCache() {
        this(8);
    }

    public OcrMemoryCache(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);

        this.cache = new LinkedHashMap<String, OcrPageResult>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, OcrPageResult> eldest) {
                return size() > OcrMemoryCache.this.maxEntries;
            }
        };
    }

    public synchronized OcrPageResult get(String key) {
        return cache.get(key);
    }

    public synchronized void put(String key, OcrPageResult value) {
        if (key == null || value == null) return;
        cache.put(key, value);
    }

    public synchronized boolean contains(String key) {
        return cache.containsKey(key);
    }

    public synchronized void clear() {
        cache.clear();
    }

    public synchronized int size() {
        return cache.size();
    }
}

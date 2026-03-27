package com.example.demo.service.impl.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 通用内存存储，便于开发阶段快速验证 CRUD 流程。
 */
public class InMemoryCrudStore<T> {

    private final ConcurrentMap<Long, T> storage = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(0);
    private final Function<T, Long> idGetter;
    private final BiConsumer<T, Long> idSetter;

    public InMemoryCrudStore(Function<T, Long> idGetter, BiConsumer<T, Long> idSetter) {
        this.idGetter = idGetter;
        this.idSetter = idSetter;
    }

    public T save(T entity) {
        Long id = idGetter.apply(entity);
        if (id == null) {
            id = idGenerator.incrementAndGet();
            idSetter.accept(entity, id);
        } else {
            Long entityId = id;
            idGenerator.updateAndGet(current -> Math.max(current, entityId));
        }
        storage.put(id, entity);
        return entity;
    }

    public Optional<T> findById(Long id) {
        return Optional.ofNullable(storage.get(id));
    }

    public List<T> findAll() {
        List<T> items = new ArrayList<>(storage.values());
        items.sort(Comparator.comparing(item -> Optional.ofNullable(idGetter.apply(item)).orElse(Long.MAX_VALUE)));
        return items;
    }

    public void deleteById(Long id) {
        storage.remove(id);
    }

    public boolean isEmpty() {
        return storage.isEmpty();
    }
}

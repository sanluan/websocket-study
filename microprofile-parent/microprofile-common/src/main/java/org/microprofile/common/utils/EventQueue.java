package org.microprofile.common.utils;

import java.util.LinkedList;

public class EventQueue<E> extends LinkedList<E> {
    private static final long serialVersionUID = 1L;
    private int limit;

    public EventQueue(int limit) {
        this.limit = limit;
    }

    @Override
    public boolean add(E o) {
        super.add(o);
        while (size() > limit) {
            super.remove();
        }
        return true;
    }
}

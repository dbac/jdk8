package com.test;

import java.util.concurrent.atomic.AtomicInteger;

public class ThreadLocalTest {

    public static void main(String[] args) {

      AtomicInteger nextHashCode =
                new AtomicInteger();
        nextHashCode.getAndAdd(2);
        System.out.println(nextHashCode);



        new Thread(new Runnable() {
            @Override
            public void run() {

                ThreadLocal<Integer>  a = new ThreadLocal();
                a.set(1);

                ThreadLocal<Integer>  b = new ThreadLocal();
                b.set(2);
                System.out.println(a.get());
            }
        }).start();



    }
}

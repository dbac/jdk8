package com.test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class LongAddrTest {

    public static void main(String[] args) throws InterruptedException {
        int num = 500;
        //testAtomicLong(num);
          testLongAddr(num);

        //AtomicLong:50000:3945  LongAddr:50000:3895   AtomicLong:100000:7519  LongAddr:100000:7425  AtomicLong:100:10 LongAddr:100:12  AtomicLong:500:46  LongAddr:500:43
    }

    public static void testLongAddr(int num) throws InterruptedException {
        long start =System.currentTimeMillis();
        final CountDownLatch countDownLatch = new CountDownLatch(num);
        final LongAdder count = new LongAdder();
        for(int i =0;i<num; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {

                    count.increment();
                    countDownLatch.countDown();

                }
            }).start();
        }
        countDownLatch.await();

        long time = System.currentTimeMillis()-start;
        System.out.println("LongAddr:"+count.longValue()+":"+time);
    }

    public static void testAtomicLong(int num) throws InterruptedException {
        long start =System.currentTimeMillis();
        final CountDownLatch countDownLatch = new CountDownLatch(num);
        final AtomicLong count = new AtomicLong(0);
        for(int i =0;i<num; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {

                    count.incrementAndGet();
                    countDownLatch.countDown();

                }
            }).start();
        }
        countDownLatch.await();

        long time = System.currentTimeMillis()-start;
        System.out.println("AtomicLong:"+count.get()+":"+time);
    }

}

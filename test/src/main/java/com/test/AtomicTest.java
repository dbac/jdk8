package com.test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class AtomicTest {

    public static void main(String[] args) throws Exception {
        int num = 300;



//atomic:220000:15974  sync:220000:15726   sync:300:29   atomic:300:27

        //testSync(num);
       testAtomic(num);
    }

    static int count = 0;
    public static synchronized void  inc(CountDownLatch countDownLatch){
        count++;
        countDownLatch.countDown();
    }

    public static  void testSync(int num) throws InterruptedException {
        long start =System.currentTimeMillis();
        final CountDownLatch countDownLatch = new CountDownLatch(num);
        Object lock = new Object();
        for(int i =0;i<num; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {

                         inc(countDownLatch);


                }
            }).start();
        }
        countDownLatch.await();

        long time = System.currentTimeMillis()-start;
        System.out.println("sync:"+count+":"+time);
    }

    public static void testAtomic(int num) throws InterruptedException {
        long start =System.currentTimeMillis();
        final CountDownLatch countDownLatch = new CountDownLatch(num);
        final AtomicInteger count = new AtomicInteger(0);
        Object lock = new Object();
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
        System.out.println("atomic:"+count.get()+":"+time);
    }


}

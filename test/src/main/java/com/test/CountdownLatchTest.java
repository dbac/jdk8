package com.test;

import java.util.concurrent.CountDownLatch;

public class CountdownLatchTest {
    public static void main(String[] args) throws InterruptedException {
        final CountDownLatch countDownLatch = new CountDownLatch(2);

        new Thread(){
            @Override
            public void run() {

                try {
                    Thread.sleep(2000000000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                System.out.println("子线程放行......");
                countDownLatch.countDown();
            }
        }.start();

        new Thread(){
            @Override
            public void run() {
                try {
                    Thread.sleep(2000000000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                System.out.println("子线程放行......");
                countDownLatch.countDown();
            }
        }.start();

        System.out.println("主线程阻塞......");
        countDownLatch.await();

        System.out.println("主线程执行......");
    }
}

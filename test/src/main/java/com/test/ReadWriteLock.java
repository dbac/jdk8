package com.test;

import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ReadWriteLock {

    public static void main(String[] args) {
        ReentrantReadWriteLock reentrantReadWriteLock = new ReentrantReadWriteLock();
        final ReentrantReadWriteLock.WriteLock  writeLock = reentrantReadWriteLock.writeLock();
        final ReentrantReadWriteLock.ReadLock readLock = reentrantReadWriteLock.readLock();

//        for(int i=0 ;i<100;i++) {
//            new Thread() {
//                @Override
//                public void run() {
//                    readLock.lock();
//
//
//                    readLock.lock();
//                }
//            }.start();
//        }

// 同一个线程先加写锁，后续可以获取读锁
        //加写锁，回阻塞其他线程的读锁以及写锁
//        new Thread(){
//            @Override
//            public void run() {
//                writeLock.lock();
//                System.out.println("获取到写锁");
//            }
//        }.start();

        readLock.lock();

        System.out.println("获取到读锁");

        System.out.println(reentrantReadWriteLock.getReadLockCount());
        readLock.unlock();
        System.out.println("释放了读锁");
    }
}

package com.test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

public class linkedblokedqueueTest {
    public static void main(String[] args) {
        LinkedBlockingQueue linkedBlockingQueue = new LinkedBlockingQueue();
        linkedBlockingQueue.offer(1);
        linkedBlockingQueue.offer(2);


        LinkedHashMap<Integer,Integer> keymap = new LinkedHashMap<Integer, Integer>(10,0.75f,true){

            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
               if(size() > 10){
                   return  true;
               }
               return  false;
            }
        };
    }
}

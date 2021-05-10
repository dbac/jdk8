package com.test;

public class HashTest {
    public static void main(String[] args) {
        int h =  "fdsfdsf".hashCode();

        System.out.println(h);

        System.out.println(Integer.toBinaryString(h));

        System.out.println(Integer.toBinaryString(h >>> 20));

        System.out.println(Integer.toBinaryString(h >>> 12 ));
         h ^= (h >>> 20) ^ (h >>> 12);

        System.out.println(Integer.toBinaryString(h ));

        System.out.println(Integer.toBinaryString(h >>> 7 ));

        System.out.println(Integer.toBinaryString(h >>> 4 ));

        h = h ^ (h >>> 7) ^ (h >>> 4);

        System.out.println(Integer.toBinaryString(h));
    }
}

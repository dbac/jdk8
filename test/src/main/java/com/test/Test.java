package com.test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public class Test  extends BaseDao<Student> {

    public void test() {
        System.out.println(clazz);
    }

    public static void main(String[] args) {
        Test test = new Test();
        test.test();

    }
}



class BaseDao<T>{
    protected Class<T> clazz;

    @SuppressWarnings("unchecked")
    public BaseDao() {
        @SuppressWarnings("rawtypes")
        Class clazz = getClass();

        while (clazz != Object.class) {
            Type t = clazz.getGenericSuperclass();
            if (t instanceof ParameterizedType) {
                Type[] args = ((ParameterizedType) t).getActualTypeArguments();
                if (args[0] instanceof Class) {
                    this.clazz = (Class<T>) args[0];
                    break;
                }
            }
            clazz = clazz.getSuperclass();
        }
    }
}



class Student {
}





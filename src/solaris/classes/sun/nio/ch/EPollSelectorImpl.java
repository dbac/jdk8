/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.ch;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.channels.spi.*;
import java.util.*;
import sun.misc.*;

/**
 * An implementation of Selector for Linux 2.6+ kernels that uses
 * the epoll event notification facility.
 */
class EPollSelectorImpl
    extends SelectorImpl
{

    // 文件描述符用来做中断
    protected int fd0;
    protected int fd1;

    // 封装了poll对象
    EPollArrayWrapper pollWrapper;

    // 维护socket文件描述符 和selectionkey的 关联关系
    private Map<Integer,SelectionKeyImpl> fdToKey;

    // 选择器是否关闭
    private volatile boolean closed = false;

    // Lock for interrupt triggering and clearing
    private final Object interruptLock = new Object();
    private boolean interruptTriggered = false;

    /**
     * Package private constructor called by factory method in
     * the abstract superclass Selector.
     */
    EPollSelectorImpl(SelectorProvider sp) throws IOException {
        super(sp);
        long pipeFds = IOUtil.makePipe(false);  //基于linux pipe机制创建管道
        fd0 = (int) (pipeFds >>> 32);  //读数据
        fd1 = (int) pipeFds;   //写数据
        //创建poll对象的包装器
        pollWrapper = new EPollArrayWrapper();
        //注册fd0,关注读事件
        pollWrapper.initInterrupt(fd0, fd1);
        //初始化 fd -> key 的关系map
        fdToKey = new HashMap<>();
    }

    protected int doSelect(long timeout) throws IOException {
        if (closed)
            throw new ClosedSelectorException();
        processDeregisterQueue(); //处理取消的Key
        try {
            begin();  //给当前线程设置中断器，如果当前线程被中断了，会调用该中断器中断逻辑。 中断逻辑是调用当前selector的wakeup（）方法
            pollWrapper.poll(timeout);
        } finally {
            end();  //把当前线程的中断器设置为null
        }
        processDeregisterQueue(); //处理取消的Key
        //更新本次select后就绪的事件数量
        int numKeysUpdated = updateSelectedKeys();
        if (pollWrapper.interrupted()) {
            // Clear the wakeup pipe
            pollWrapper.putEventOps(pollWrapper.interruptedIndex(), 0);
            synchronized (interruptLock) {
                pollWrapper.clearInterrupted();
                IOUtil.drain(fd0);
                interruptTriggered = false;
            }
        }
        return numKeysUpdated;
    }

    /**
     * Update the keys whose fd's have been selected by the epoll.
     * Add the ready keys to the ready queue.
     */
    private int updateSelectedKeys() {
        int entries = pollWrapper.updated;  //多少个事件发生
        int numKeysUpdated = 0;
        for (int i=0; i<entries; i++) {  //遍历
            //获取事件对应的fd
            int nextFD = pollWrapper.getDescriptor(i);
            //获取fd对应的key
            SelectionKeyImpl ski = fdToKey.get(Integer.valueOf(nextFD));
            // ski is null in the case of an interrupt
            if (ski != null) {
                //获取fd对应关注感兴趣的事件
                int rOps = pollWrapper.getEventOps(i);
                //如果包含在selectedKeys里   刚开始肯定不包含
                if (selectedKeys.contains(ski)) {
                         // 将底层的事件转换为Java封装的事件,SelectionKey.OP_READ等
                    if (ski.channel.translateAndSetReadyOps(rOps, ski)) {
                        //如果之前的事件处理完了，没有删除，这里数量也会加1，说明还会被处理   程序中处理完了这个操作，要从selectedkeys里移除
                        numKeysUpdated++;
                    }
                } else {
                    //// 没有在原有的SelectedKey里面，说明是在等待过程中加入的
                    ///本次select 新加入的key发生了
                    // 把底层的epoll事件转化成java封装的操作SelectionKey.OP_READ等
                    ski.channel.translateAndSetReadyOps(rOps, ski);
                    //就绪事件是感兴趣的事件
                    if ((ski.nioReadyOps() & ski.nioInterestOps()) != 0) {
                        selectedKeys.add(ski);  //添加到感兴趣key里
                        numKeysUpdated++;
                    }
                }
            }
        }
        return numKeysUpdated;
    }

    protected void implClose() throws IOException {
        if (closed)
            return;
        closed = true;

        // prevent further wakeup
        synchronized (interruptLock) {
            interruptTriggered = true;
        }

        FileDispatcherImpl.closeIntFD(fd0);
        FileDispatcherImpl.closeIntFD(fd1);

        pollWrapper.closeEPollFD();
        // it is possible
        selectedKeys = null;

        // Deregister channels
        Iterator<SelectionKey> i = keys.iterator();
        while (i.hasNext()) {
            SelectionKeyImpl ski = (SelectionKeyImpl)i.next();
            deregister(ski);
            SelectableChannel selch = ski.channel();
            if (!selch.isOpen() && !selch.isRegistered())
                ((SelChImpl)selch).kill();
            i.remove();
        }

        fd0 = -1;
        fd1 = -1;
    }

    protected void implRegister(SelectionKeyImpl ski) {
        if (closed)
            throw new ClosedSelectorException();
        SelChImpl ch = ski.channel;  //获取通道
        int fd = Integer.valueOf(ch.getFDVal()); //获取通道的文件描述符
        fdToKey.put(fd, ski); //把文件描述符和key关联起来
        pollWrapper.add(fd); //poll数组里添加这个文件描述符
        keys.add(ski); // 添加key
    }

    //移除已经取消的key
    protected void implDereg(SelectionKeyImpl ski) throws IOException {
        assert (ski.getIndex() >= 0);
        SelChImpl ch = ski.channel;
        int fd = ch.getFDVal();
        fdToKey.remove(Integer.valueOf(fd));
        pollWrapper.remove(fd);
        ski.setIndex(-1);
        keys.remove(ski);
        selectedKeys.remove(ski);
        deregister((AbstractSelectionKey)ski);
        SelectableChannel selch = ski.channel();
        if (!selch.isOpen() && !selch.isRegistered())
            ((SelChImpl)selch).kill();
    }

    //把感兴趣的事件设置到对应通道的文件描述符里
    public void putEventOps(SelectionKeyImpl ski, int ops) {
        if (closed)
            throw new ClosedSelectorException();
        SelChImpl ch = ski.channel;
        //
        pollWrapper.setInterest(ch.getFDVal(), ops);
    }

    public Selector wakeup() {  //唤醒当前selector 的select方法,从阻塞中断退出
        synchronized (interruptLock) {
            if (!interruptTriggered) {
                pollWrapper.interrupt();
                interruptTriggered = true;
            }
        }
        return this;
    }
}

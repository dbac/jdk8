/*
 * Copyright (c) 2002, 2013, Oracle and/or its affiliates. All rights reserved.
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

/*
 */


package sun.nio.ch;

import java.nio.channels.spi.SelectorProvider;
import java.nio.channels.Selector;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.Pipe;
import java.nio.channels.SelectableChannel;
import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/**
 * A multi-threaded implementation of Selector for Windows.
 *
 * @author Konstantin Kladko
 * @author Mark Reinhold
 */

final class WindowsSelectorImpl extends SelectorImpl {


    private final int INIT_CAP = 8;

    //每个线程处理channel的数量
    private final static int MAX_SELECTABLE_FDS = 1024;



    //注册的selectionKey数组
    private SelectionKeyImpl[] channelArray = new SelectionKeyImpl[INIT_CAP];

    //底层的本机轮询数组包装对象，用于存放Socket文件描述符和事件掩码
    private PollArrayWrapper pollWrapper;

    private int totalChannels = 1;
    private int threadsCount = 0;

    //辅助线程数组
    private final List<SelectThread> threads = new ArrayList<SelectThread>();

    //用于唤醒辅助线程
    private final Pipe wakeupPipe;

    //唤醒线程的文件描述符
    private final int wakeupSourceFd, wakeupSinkFd;

    private Object closeLock = new Object();

    private final static class FdMap extends HashMap<Integer, MapEntry> {
        static final long serialVersionUID = 0L;
        private MapEntry get(int desc) {
            return get(new Integer(desc));
        }
        private MapEntry put(SelectionKeyImpl ski) {
            return put(new Integer(ski.channel.getFDVal()), new MapEntry(ski));
        }
        private MapEntry remove(SelectionKeyImpl ski) {
            Integer fd = new Integer(ski.channel.getFDVal());
            MapEntry x = get(fd);
            if ((x != null) && (x.ski.channel == ski.channel))
                return remove(fd);
            return null;
        }
    }

    private final static class MapEntry {
        SelectionKeyImpl ski;
        long updateCount = 0;
        long clearedCount = 0;
        MapEntry(SelectionKeyImpl ski) {
            this.ski = ski;
        }
    }

    //保存文件描述符和SelectionKey的映射关系
    private final FdMap fdMap = new FdMap();

    //调用JNI的poll和处理就绪的SelectionKey
    private final SubSelector subSelector = new SubSelector();

    private long timeout;

    private final Object interruptLock = new Object();
    private volatile boolean interruptTriggered = false;

    WindowsSelectorImpl(SelectorProvider sp) throws IOException {
        super(sp);
        //首先创建了一个默认8个长度(8*8字节)的文件描述符数组PollArrayWrapper
        pollWrapper = new PollArrayWrapper(INIT_CAP);
        //创建一个Pipe，Pipe是一个单向通讯管道。
        wakeupPipe = Pipe.open();

        //获取Pipe的源端和目标端的文件描述符句柄，该句柄用于激活线程。
        wakeupSourceFd = ((SelChImpl)wakeupPipe.source()).getFDVal();

        SinkChannelImpl sink = (SinkChannelImpl)wakeupPipe.sink();
        (sink.sc).socket().setTcpNoDelay(true);

        wakeupSinkFd = ((SelChImpl)sink).getFDVal();


        //将wakeupSourceFd存到PollArrayWapper每1024个元素的第一个位置。使得每个线程都能被wakeupSourceFd唤醒。
        //由于select最大支持1024个句柄，这里第一个文件描述符是wakeupSourceFd，所以一个线程实际最多并发处理1023个socket文件描述符。
        pollWrapper.addWakeupSocket(wakeupSourceFd, 0);
    }

    protected int doSelect(long timeout) throws IOException {
        if (channelArray == null)
            throw new ClosedSelectorException();
        this.timeout = timeout;
        //1. 删除取消的key
        processDeregisterQueue();
        if (interruptTriggered) {
            resetWakeupSocket();
            return 0;
        }
        //2. 调整线程数 ，等待运行
        adjustThreadsCount();
        //3. 设置辅助线程数
        finishLock.reset();
        //4. 开始运行新增的辅助线程

        startLock.startThreads();

        try {
            begin();
            try {
                //5. 获取就绪文件描述符
                subSelector.poll();
            } catch (IOException e) {
                finishLock.setException(e); // Save this exception
            }

            //6. 等待所有辅助线程完成
            if (threads.size() > 0)
                finishLock.waitForHelperThreads();
          } finally {
              end();
          }
        finishLock.checkForException();
        //7. 再次检查删除取消的key
        processDeregisterQueue();
        //8. 将就绪的key加入到selectedKeys中
        int updated = updateSelectedKeys();
        // 完成，重置唤醒标记下次在运行。
        resetWakeupSocket();
        return updated;
    }

    private final StartLock startLock = new StartLock();

    private final class StartLock {

        private long runsCounter;
        private synchronized void startThreads() {
            runsCounter++; // next run
            notifyAll(); // wake up threads.
        }

        private synchronized boolean waitForStart(SelectThread thread) {
            while (true) {
                while (runsCounter == thread.lastRun) {
                    try {
                        startLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (thread.isZombie()) { // redundant thread
                    return true; // will cause run() to exit.
                } else {
                    thread.lastRun = runsCounter; // update lastRun
                    return false; //   will cause run() to poll.
                }
            }
        }
    }


    private final FinishLock finishLock = new FinishLock();

    private final class FinishLock  {
        // Number of helper threads, that did not finish yet.
        private int threadsToFinish;

        // IOException which occurred during the last run.
        IOException exception = null;

        // Called before polling.
        private void reset() {
            threadsToFinish = threads.size(); // helper threads
        }

        // Each helper thread invokes this function on finishLock, when
        // the thread is done with poll().
        private synchronized void threadFinished() {
            if (threadsToFinish == threads.size()) { // finished poll() first
                // if finished first, wakeup others
                wakeup();
            }
            threadsToFinish--;
            if (threadsToFinish == 0) // all helper threads finished poll().
                notify();             // notify the main thread
        }

        // The main thread invokes this function on finishLock to wait
        // for helper threads to finish poll().
        private synchronized void waitForHelperThreads() {
            if (threadsToFinish == threads.size()) {
                // no helper threads finished yet. Wakeup them up.
                wakeup();
            }
            while (threadsToFinish != 0) {
                try {
                    finishLock.wait();
                } catch (InterruptedException e) {
                    // Interrupted - set interrupted state.
                    Thread.currentThread().interrupt();
                }
            }
        }

        // sets IOException for this run
        private synchronized void setException(IOException e) {
            exception = e;
        }

        // Checks if there was any exception during the last run.
        // If yes, throws it
        private void checkForException() throws IOException {
            if (exception == null)
                return;
            StringBuffer message =  new StringBuffer("An exception occurred" +
                                       " during the execution of select(): \n");
            message.append(exception);
            message.append('\n');
            exception = null;
            throw new IOException(message.toString());
        }
    }


    //SubSelector对象封装了poll调用的逻辑和获取就绪selectionKey的方法。主线程和每个辅助线程都有自己的subSelector对象
    private final class SubSelector {

        //该线程管理的selectionKey数组区域的起始地址
        private final int pollArrayIndex;

        //可读文件描述符数组
        private final int[] readFds = new int [MAX_SELECTABLE_FDS + 1];
        //可写文件描述符数组
        private final int[] writeFds = new int [MAX_SELECTABLE_FDS + 1];
        //异常文件描述符数组
        private final int[] exceptFds = new int [MAX_SELECTABLE_FDS + 1];

        private SubSelector() {
            this.pollArrayIndex = 0; // main thread
        }

        private SubSelector(int threadIndex) { // helper threads
            this.pollArrayIndex = (threadIndex + 1) * MAX_SELECTABLE_FDS;
        }

        //主线poll调用所使用的方法

        //会监听pollWrapper中的FD有没有数据进出，这会造成IO阻塞，直到有数据读写事件发生。
        // 比如，由于pollWrapper中保存的也有ServerSocketChannel的FD，所以只要ClientSocket发一份数据到ServerSocket,那么poll0（）就会返回；
        // 又由于pollWrapper中保存的也有pipe的write端的FD，所以只要pipe的write端向FD发一份数据，也会造成poll0（）返回；
        // 如果这两种情况都没有发生，那么poll0（）就一直阻塞，也就是selector.select()会一直阻塞；
        // 如果有任何一种情况发生，那么selector.select()就会返回
        private int poll() throws IOException{
            return poll0(pollWrapper.pollArrayAddress,
                         Math.min(totalChannels, MAX_SELECTABLE_FDS),
                         readFds, writeFds, exceptFds, timeout);
        }

        //辅助线程poll调用所使用的方法
        private int poll(int index) throws IOException {

            //数组起始偏移量
            //要处理的文件描述符数量
            return  poll0(pollWrapper.pollArrayAddress +
                     (pollArrayIndex * PollArrayWrapper.SIZE_POLLFD),
                     Math.min(MAX_SELECTABLE_FDS,
                             totalChannels - (index + 1) * MAX_SELECTABLE_FDS),
                     readFds, writeFds, exceptFds, timeout);
        }

        private native int poll0(long pollAddress, int numfds,
             int[] readFds, int[] writeFds, int[] exceptFds, long timeout);

        private int processSelectedKeys(long updateCount) {
            int numKeysUpdated = 0;
            numKeysUpdated += processFDSet(updateCount, readFds,
                                           PollArrayWrapper.POLLIN,
                                           false);
            numKeysUpdated += processFDSet(updateCount, writeFds,
                                           PollArrayWrapper.POLLCONN |
                                           PollArrayWrapper.POLLOUT,
                                           false);
            numKeysUpdated += processFDSet(updateCount, exceptFds,
                                           PollArrayWrapper.POLLIN |
                                           PollArrayWrapper.POLLCONN |
                                           PollArrayWrapper.POLLOUT,
                                           true);
            return numKeysUpdated;
        }


        private int processFDSet(long updateCount, int[] fds, int rOps,
                                 boolean isExceptFds)
        {
            int numKeysUpdated = 0;
            //1. 遍历文件描述符数组
            for (int i = 1; i <= fds[0]; i++) {
                //获取文件描述符句柄值
                int desc = fds[i];
                //2. 判断当前文件描述符是否是用于唤醒的文件描述
                if (desc == wakeupSourceFd) {
                    synchronized (interruptLock) {
                        interruptTriggered = true;
                    }
                    continue;
                }
                //3. 获取文件描述符句柄对应的SelectionKey的映射值
                MapEntry me = fdMap.get(desc);
                // 4. 若为空，则表示已经被取消。
                if (me == null)
                    continue;
                SelectionKeyImpl sk = me.ski;

                // 5. 丢弃OOD数据(紧急数据)
                if (isExceptFds &&
                    (sk.channel() instanceof SocketChannelImpl) &&
                    discardUrgentData(desc))
                {
                    continue;
                }
                //6. 判断key是否已经就绪，若已就绪，则将当前操作累加到原来的操作上，比如原来写事件就绪，现在读事件就绪，就需要更新该key读写就绪
                if (selectedKeys.contains(sk)) {
                    //clearedCount 和 updateCount用于避免同一个key的事件设置多次，因为同一个文件描述符可能在可读文件描述符数组也可能在异常文件描述符数组中。
                    if (me.clearedCount != updateCount) {
                        if (sk.channel.translateAndSetReadyOps(rOps, sk) &&
                            (me.updateCount != updateCount)) {
                            me.updateCount = updateCount;
                            numKeysUpdated++;
                        }
                    } else { // The readyOps have been set; now add
                        if (sk.channel.translateAndUpdateReadyOps(rOps, sk) &&
                            (me.updateCount != updateCount)) {
                            me.updateCount = updateCount;
                            numKeysUpdated++;
                        }
                    }
                    me.clearedCount = updateCount;
                } else {
                    //key原来未就绪，将key加入selectedKeys中
                    if (me.clearedCount != updateCount) {
                        sk.channel.translateAndSetReadyOps(rOps, sk);
                        if ((sk.nioReadyOps() & sk.nioInterestOps()) != 0) {
                            selectedKeys.add(sk);
                            me.updateCount = updateCount;
                            numKeysUpdated++;
                        }
                    } else { // The readyOps have been set; now add
                        sk.channel.translateAndUpdateReadyOps(rOps, sk);
                        if ((sk.nioReadyOps() & sk.nioInterestOps()) != 0) {
                            selectedKeys.add(sk);
                            me.updateCount = updateCount;
                            numKeysUpdated++;
                        }
                    }
                    me.clearedCount = updateCount;
                }
            }
            return numKeysUpdated;
        }
    }

    private final class SelectThread extends Thread {
        private final int index; // index of this thread
        final SubSelector subSelector;
        private long lastRun = 0; // last run number
        private volatile boolean zombie;
        // Creates a new thread
        private SelectThread(int i) {
            this.index = i;
            this.subSelector = new SubSelector(i);
            //make sure we wait for next round of poll
            this.lastRun = startLock.runsCounter;
        }
        void makeZombie() {
            zombie = true;
        }
        boolean isZombie() {
            return zombie;
        }
        public void run() {
            while (true) { // poll loop
                // wait for the start of poll. If this thread has become
                // redundant, then exit.
                if (startLock.waitForStart(this))
                    return;
                // call poll()
                try {
                    subSelector.poll(index);
                } catch (IOException e) {
                    // Save this exception and let other threads finish.
                    finishLock.setException(e);
                }
                // notify main thread, that this thread has finished, and
                // wakeup others, if this thread is the first to finish.
                finishLock.threadFinished();
            }
        }
    }


    private void adjustThreadsCount() {
        if (threadsCount > threads.size()) {
            // More threads needed. Start more threads.
            for (int i = threads.size(); i < threadsCount; i++) {
                SelectThread newThread = new SelectThread(i);
                threads.add(newThread);
                newThread.setDaemon(true);
                newThread.start();
            }
        } else if (threadsCount < threads.size()) {
            // Some threads become redundant. Remove them from the threads List.
            for (int i = threads.size() - 1 ; i >= threadsCount; i--)
                threads.remove(i).makeZombie();
        }
    }


    private void setWakeupSocket() {
        setWakeupSocket0(wakeupSinkFd);
    }
    private native void setWakeupSocket0(int wakeupSinkFd);

    private void resetWakeupSocket() {
        synchronized (interruptLock) {
            if (interruptTriggered == false)
                return;
            resetWakeupSocket0(wakeupSourceFd);
            interruptTriggered = false;
        }
    }

    private native void resetWakeupSocket0(int wakeupSourceFd);

    private native boolean discardUrgentData(int fd);


    private long updateCount = 0;


    private int updateSelectedKeys() {
        updateCount++;
        int numKeysUpdated = 0;
        numKeysUpdated += subSelector.processSelectedKeys(updateCount);
        for (SelectThread t: threads) {
            numKeysUpdated += t.subSelector.processSelectedKeys(updateCount);
        }
        return numKeysUpdated;
    }

    protected void implClose() throws IOException {
        synchronized (closeLock) {
            if (channelArray != null) {
                if (pollWrapper != null) {
                    // prevent further wakeup
                    synchronized (interruptLock) {
                        interruptTriggered = true;
                    }
                    wakeupPipe.sink().close();
                    wakeupPipe.source().close();
                    for(int i = 1; i < totalChannels; i++) { // Deregister channels
                        if (i % MAX_SELECTABLE_FDS != 0) { // skip wakeupEvent
                            deregister(channelArray[i]);
                            SelectableChannel selch = channelArray[i].channel();
                            if (!selch.isOpen() && !selch.isRegistered())
                                ((SelChImpl)selch).kill();
                        }
                    }
                    pollWrapper.free();
                    pollWrapper = null;
                    selectedKeys = null;
                    channelArray = null;
                    // Make all remaining helper threads exit
                    for (SelectThread t: threads)
                         t.makeZombie();
                    startLock.startThreads();
                }
            }
        }
    }

    protected void implRegister(SelectionKeyImpl ski) {
        synchronized (closeLock) {
            if (pollWrapper == null)
                throw new ClosedSelectorException();

            //判断是否需要扩容队列以及添加辅助线程
            //在注册之前会先会判断当前注册的Channel数量 是否达到需要启动辅助线程的阈值。
            // 如果达到阈值则需要扩容pollWrapper数组，同时还要 将wakeupSourceFd加入到扩容后的第一个位置
            growIfNeeded();

            //保存到缓存中
            channelArray[totalChannels] = ski;


            //保存在数组中的位置
            ski.setIndex(totalChannels);

            //保存文件描述符和SelectionKeyImpl的映射关系到FDMap
            fdMap.put(ski);

            //保存到keys中
            keys.add(ski);

            //将key的文件描述符添加到pollWrapper对象中
            pollWrapper.addEntry(totalChannels, ski);
            totalChannels++;
        }
    }

    private void growIfNeeded() {

        //channel数组已满,扩容两倍
        if (channelArray.length == totalChannels) {

            //扩大两倍
            int newSize = totalChannels * 2; // Make a larger array
            SelectionKeyImpl temp[] = new SelectionKeyImpl[newSize];
            System.arraycopy(channelArray, 1, temp, 1, totalChannels - 1);
            channelArray = temp;

            //文件描述符数组扩容
            pollWrapper.grow(newSize);
        }

        //如果数组中大小为每个线程能够处理的最大通道数量的整数倍，则将唤醒线程的文件描述符添加到totalChannels位置
        if (totalChannels % MAX_SELECTABLE_FDS == 0) { // more threads needed

            //将唤醒的文件描述符加入到扩容后的第一个位置。
            pollWrapper.addWakeupSocket(wakeupSourceFd, totalChannels);
            totalChannels++;
            //添加线程数

            threadsCount++;
        }
    }

    protected void implDereg(SelectionKeyImpl ski) throws IOException{
        int i = ski.getIndex();
        assert (i >= 0);
        synchronized (closeLock) {
            if (i != totalChannels - 1) {
                // Copy end one over it
                SelectionKeyImpl endChannel = channelArray[totalChannels-1];
                channelArray[i] = endChannel;
                endChannel.setIndex(i);
                pollWrapper.replaceEntry(pollWrapper, totalChannels - 1,
                                                                pollWrapper, i);
            }
            ski.setIndex(-1);
        }
        channelArray[totalChannels - 1] = null;
        totalChannels--;
        if ( totalChannels != 1 && totalChannels % MAX_SELECTABLE_FDS == 1) {
            totalChannels--;
            threadsCount--; // The last thread has become redundant.
        }
        fdMap.remove(ski); // Remove the key from fdMap, keys and selectedKeys
        keys.remove(ski);
        selectedKeys.remove(ski);
        deregister(ski);
        SelectableChannel selch = ski.channel();
        if (!selch.isOpen() && !selch.isRegistered())
            ((SelChImpl)selch).kill();
    }

    public void putEventOps(SelectionKeyImpl sk, int ops) {
        synchronized (closeLock) {
            if (pollWrapper == null)
                throw new ClosedSelectorException();
            // make sure this sk has not been removed yet
            int index = sk.getIndex();
            if (index == -1)
                throw new CancelledKeyException();
            pollWrapper.putEventOps(index, ops);
        }
    }

    public Selector wakeup() {
        synchronized (interruptLock) {
            if (!interruptTriggered) {
                setWakeupSocket();
                interruptTriggered = true;
            }
        }
        return this;
    }

    static {
        IOUtil.load();
    }
}

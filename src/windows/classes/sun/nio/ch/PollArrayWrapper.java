/*
 * Copyright (c) 2001, 2013, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.annotation.Native;

/**
 * Manipulates a native array of structs corresponding to (fd, events) pairs.
 *
 * typedef struct pollfd {
 *    SOCKET fd;            // 4 bytes
 *    short events;         // 2 bytes
 * } pollfd_t;
 *
 *
 * @author Konstantin Kladko
 * @author Mike McCloskey
 */
//在pollArray中，每条记录占8个字节，前四个字节存放文件描述符，接着使用两个字节存放等待事件的掩码，最后两个字节存放事件发生事件的掩码（目前未使用）
class PollArrayWrapper {

    //用户存储文件描述符数组的native空间
    private AllocatedNativeObject pollArray;

    //上述内存空间的地址
    long pollArrayAddress;

    //每条记录中文件描述符的相对起始偏移量
    @Native private static final short FD_OFFSET     = 0;

    //每条记录中事件掩码的相对起始偏移量
    @Native private static final short EVENT_OFFSET  = 4;

    //每条记录所占空间大小为8个字节
    static short SIZE_POLLFD = 8;

    // events masks
    @Native static final short POLLIN     = AbstractPollArrayWrapper.POLLIN;
    @Native static final short POLLOUT    = AbstractPollArrayWrapper.POLLOUT;
    @Native static final short POLLERR    = AbstractPollArrayWrapper.POLLERR;
    @Native static final short POLLHUP    = AbstractPollArrayWrapper.POLLHUP;
    @Native static final short POLLNVAL   = AbstractPollArrayWrapper.POLLNVAL;
    @Native static final short POLLREMOVE = AbstractPollArrayWrapper.POLLREMOVE;
    @Native static final short POLLCONN   = 0x0002;

    //数组大小
    private int size;

    PollArrayWrapper(int newSize) {
        int allocationSize = newSize * SIZE_POLLFD;
        pollArray = new AllocatedNativeObject(allocationSize, true);
        pollArrayAddress = pollArray.address();
        this.size = newSize;
    }

    // Prepare another pollfd struct for use.
    void addEntry(int index, SelectionKeyImpl ski) {
        putDescriptor(index, ski.channel.getFDVal());
    }

    // Writes the pollfd entry from the source wrapper at the source index
    // over the entry in the target wrapper at the target index.
    void replaceEntry(PollArrayWrapper source, int sindex,
                                     PollArrayWrapper target, int tindex) {
        target.putDescriptor(tindex, source.getDescriptor(sindex));
        target.putEventOps(tindex, source.getEventOps(sindex));
    }

    // Grows the pollfd array to new size
    void grow(int newSize) {
        //创建新的数组
        PollArrayWrapper temp = new PollArrayWrapper(newSize);

        for (int i = 0; i < size; i++)
            replaceEntry(this, i, temp, i); //将原来的数组的内容存放到新的数组中
        pollArray.free();  //释放原来的数组
        pollArray = temp.pollArray;  //更新引用
        this.size = temp.size;     //更新大小
        pollArrayAddress = pollArray.address();     //更新地址

    }

    void free() {
        pollArray.free();
    }

    // Access methods for fd structures
    void putDescriptor(int i, int fd) {
        pollArray.putInt(SIZE_POLLFD * i + FD_OFFSET, fd);
    }

    void putEventOps(int i, int event) {
        pollArray.putShort(SIZE_POLLFD * i + EVENT_OFFSET, (short)event);
    }

    int getEventOps(int i) {
        return pollArray.getShort(SIZE_POLLFD * i + EVENT_OFFSET);
    }

    int getDescriptor(int i) {
       return pollArray.getInt(SIZE_POLLFD * i + FD_OFFSET);
    }

    // Adds Windows wakeup socket at a given index.
    void addWakeupSocket(int fdVal, int index) {
        putDescriptor(index, fdVal);
        putEventOps(index, POLLIN);
    }
}

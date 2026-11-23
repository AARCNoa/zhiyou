package com.tencent.mm.protocal.protobuf;

import java.util.LinkedList;

public class SnsObject {

    public String Username;
    /** 该条朋友圈的 snsId（负数），库里的 `SnsInfo.snsId` 与它同值。 */
    public long Id;
    public LinkedList<Object> LikeUserList = new LinkedList<>();
    public LinkedList<Object> CommentUserList = new LinkedList<>();
    public int LikeUserListCount;
    public int LikeCount;
    public int LikeFlag;
    public int CommentUserListCount;
    public int CommentCount;

    public byte[] toByteArray() {
        throw new RuntimeException("Stub!");
    }
}

package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;
import com.fongmi.android.tv.bean.Download;
import java.util.List;

@Dao
public abstract class DownloadDao extends BaseDao<Download> {

    @Query("SELECT * FROM Download ORDER BY createTime DESC")
    public abstract List<Download> getAll();

    @Query("SELECT * FROM Download WHERE status = :status ORDER BY createTime DESC")
    public abstract List<Download> getByStatus(int status);

    @Query("SELECT * FROM Download WHERE id = :id")
    public abstract Download find(int id);

    @Query("DELETE FROM Download WHERE id = :id")
    public abstract void delete(int id);

    @Query("DELETE FROM Download")
    public abstract void delete();
}

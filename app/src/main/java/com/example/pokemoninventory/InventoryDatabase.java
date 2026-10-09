package com.example.pokemoninventory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Entity;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.PrimaryKey;
import androidx.room.Query;
import androidx.room.RoomDatabase;
import java.util.List;

// All Room declarations are Java so the standard Java annotation processor can generate them.
@Database(entities = {InventoryDatabase.Item.class, InventoryDatabase.Product.class,
        InventoryDatabase.Job.class, InventoryDatabase.Request.class, InventoryDatabase.Setting.class},
        version = 1, exportSchema = false)
public abstract class InventoryDatabase extends RoomDatabase {
    public abstract Store store();

    @Entity(tableName = "inventory_items")
    public static class Item {
        @PrimaryKey @NonNull public String id = "";
        @NonNull public String upc = "";
        @NonNull public String name = "Unknown product";
        public int quantity = 1;
        public boolean nameEdited;
        @Nullable public String imageFileName;
    }

    @Entity(tableName = "products")
    public static class Product {
        @PrimaryKey @NonNull public String upc = "";
        @Nullable public String title;
        public boolean found;
        @NonNull public String imageUrls = "[]";
        @Nullable public String imageFileName;
    }

    @Entity(tableName = "jobs", primaryKeys = {"upc", "kind"})
    public static class Job {
        @NonNull public String upc = "";
        @NonNull public String barcode = "";
        @NonNull public String kind = "lookup";
        @NonNull public String state = "queued";
        public int attempts;
        public long createdAt;
        public long nextAttemptAt;
    }

    @Entity(tableName = "requests")
    public static class Request {
        @PrimaryKey(autoGenerate = true) public long id;
        public long attemptedAt;
    }

    @Entity(tableName = "settings")
    public static class Setting {
        @PrimaryKey @NonNull public String key = "";
        public long value;
    }

    @Dao
    public interface Store {
        @Query("SELECT * FROM inventory_items") List<Item> items();
        @Query("SELECT * FROM inventory_items WHERE id = :id") Item item(String id);
        @Query("SELECT * FROM inventory_items WHERE upc = :upc LIMIT 1") Item itemForUpc(String upc);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void putItem(Item item);
        @Query("DELETE FROM inventory_items WHERE id = :id") void deleteItem(String id);
        @Query("SELECT * FROM products WHERE upc = :upc") Product product(String upc);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void putProduct(Product product);
        @Query("DELETE FROM products WHERE upc = :upc") void deleteProduct(String upc);
        @Query("SELECT * FROM jobs WHERE upc = :upc AND kind = :kind") Job job(String upc, String kind);
        @Query("SELECT * FROM jobs ORDER BY createdAt, upc") List<Job> jobs();
        @Insert(onConflict = OnConflictStrategy.IGNORE) void enqueue(Job job);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void putJob(Job job);
        @Query("DELETE FROM jobs WHERE upc = :upc AND kind = :kind") void deleteJob(String upc, String kind);
        @Query("UPDATE jobs SET state = 'queued' WHERE state = 'running'") void recoverJobs();
        @Query("SELECT attemptedAt FROM requests ORDER BY attemptedAt") List<Long> requests();
        @Insert void reserve(Request request);
        @Query("DELETE FROM requests WHERE attemptedAt < :before") void pruneRequests(long before);
        @Query("SELECT value FROM settings WHERE `key` = :key") Long setting(String key);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void putSetting(Setting setting);
    }
}

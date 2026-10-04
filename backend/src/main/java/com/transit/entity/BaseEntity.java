package com.transit.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * 实体基类：SQLite 原生 rowid 主键（IDENTITY）。
 * 不用 Hibernate 表序列：表序列会在 JTA 事务外另借连接查询 *_SEQ，
 * 与 SQLite 单连接池自死锁。
 * Panache 静态 DAO 方法（persist/find/list/count/findById …）对 PanacheEntityBase
 * 子类由增强器自动注入，使用方式与 PanacheEntity 一致。
 */
@MappedSuperclass
public abstract class BaseEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
}

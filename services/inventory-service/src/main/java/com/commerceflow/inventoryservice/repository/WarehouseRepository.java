package com.commerceflow.inventoryservice.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.commerceflow.inventoryservice.entity.Warehouse;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    Optional<Warehouse> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    /** Everywhere new orders may be allocated from, best first. */
    List<Warehouse> findByActiveTrueOrderByPriorityAscCodeAsc();

    List<Warehouse> findAllByOrderByPriorityAscCodeAsc();
}

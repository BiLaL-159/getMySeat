package com.getmyseat.booking;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface HoldRepository extends JpaRepository<Hold, UUID> {

}

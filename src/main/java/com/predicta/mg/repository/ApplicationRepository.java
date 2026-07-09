package com.predicta.mg.repository;

import com.predicta.mg.models.Application;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

  Optional<Application> findByApiKey(String apiKey);
}

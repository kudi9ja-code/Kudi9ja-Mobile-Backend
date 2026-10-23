package com.quadrilateral.kudi9ja.domain.legal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgreementSignatureRepository extends JpaRepository<AgreementSignature, UUID> {

    List<AgreementSignature> findByUserIdOrderBySignedAtDesc(UUID userId);
}

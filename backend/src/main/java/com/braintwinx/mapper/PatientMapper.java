package com.braintwinx.mapper;

import com.braintwinx.dto.PatientResponse;
import com.braintwinx.entity.Patient;
import org.springframework.stereotype.Component;

/**
 * Converts {@link Patient} entities to API responses.
 *
 * <p>Hand-written rather than generated or reflective, and that is the point: the mapping is the
 * boundary that decides what leaves the system. A reflective mapper that copies matching field
 * names would silently start exposing any field added to the entity later — including the internal
 * id or the creating user. Here, adding a field to the response requires editing this class.
 *
 * <p>Mapping is deliberately one-way. There is no entity-from-DTO method: creating a
 * {@link Patient} requires a creating {@link com.braintwinx.entity.User}, which only the service
 * layer can resolve, and updates go through the entity's own guarded mutators so its invariants
 * (such as status and archive timestamp agreeing) cannot be bypassed.
 */
@Component
public class PatientMapper {

    /**
     * @param patient the entity to expose
     * @return the API representation, carrying no internal id and no creating user
     */
    public PatientResponse toResponse(Patient patient) {
        return new PatientResponse(
                patient.getPatientCode(),
                patient.getBirthYear(),
                patient.getSex(),
                patient.getStatus(),
                patient.getCreatedAt(),
                patient.getUpdatedAt(),
                patient.getArchivedAt());
    }
}

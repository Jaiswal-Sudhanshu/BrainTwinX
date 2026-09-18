package com.braintwinx.exception;

/**
 * A requested resource does not exist, or the caller may not see it.
 *
 * <p>Used for both cases on purpose. Distinguishing "not found" from "exists but forbidden"
 * would let a caller probe for the existence of records they cannot access — the IDOR
 * information leak in brief section 54. Returning 404 for both keeps the two indistinguishable.
 */
public class ResourceNotFoundException extends ApiException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(ApiErrorCode errorCode, String internalMessage) {
        super(errorCode, internalMessage);
    }

    public static ResourceNotFoundException patient(String patientCode) {
        return new ResourceNotFoundException(ApiErrorCode.PATIENT_NOT_FOUND,
                "Patient not found or not accessible: " + patientCode);
    }

    public static ResourceNotFoundException scan(String publicId) {
        return new ResourceNotFoundException(ApiErrorCode.SCAN_NOT_FOUND,
                "Scan not found or not accessible: " + publicId);
    }

    public static ResourceNotFoundException report(String publicId) {
        return new ResourceNotFoundException(ApiErrorCode.REPORT_NOT_FOUND,
                "Report not found or not accessible: " + publicId);
    }

    public static ResourceNotFoundException prediction(String scanPublicId) {
        return new ResourceNotFoundException(ApiErrorCode.RESOURCE_NOT_FOUND,
                "Prediction not found for scan: " + scanPublicId);
    }

    public static ResourceNotFoundException job(String jobPublicId) {
        return new ResourceNotFoundException(ApiErrorCode.RESOURCE_NOT_FOUND,
                "Analysis job not found: " + jobPublicId);
    }
}

package com.positivity.people.internal.client;

import com.positivity.people.internal.client.dto.WorkexecJobTimeTotal;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
public class WorkexecJobTimeClient {

	private final RestClient restClient;

	public WorkexecJobTimeClient(@Qualifier("workexecRestClient") RestClient restClient) {
		this.restClient = restClient;
	}

	@NonNull public List<WorkexecJobTimeTotal> getJobTimeTotals(@NonNull LocalDate startDate, @NonNull LocalDate endDate,
			@NonNull String timezone, UUID locationId, @NonNull List<UUID> technicianIds) {
		try {
			List<WorkexecJobTimeTotal> response = restClient.get().uri(uriBuilder -> {
				var builder = uriBuilder.path("/v1/workexec/job-time-totals")
					.queryParam("startDate", startDate)
					.queryParam("endDate", endDate)
					.queryParam("timezone", timezone);

				if (locationId != null) {
					builder.queryParam("locationId", locationId);
				}
				if (!technicianIds.isEmpty()) {
					builder.queryParam("technicianIds", technicianIds.toArray());
				}

				return builder.build();
			}).retrieve().onStatus(HttpStatusCode::isError, (request, responseError) -> {
				int status = responseError.getStatusCode().value();
				throw new WorkexecClientException("Work Execution request failed with status " + status, status,
						mapErrorCode(status));
			}).body(new ParameterizedTypeReference<List<WorkexecJobTimeTotal>>() {
			});

			return response == null ? List.of() : response;
		}
		catch (WorkexecClientException ex) {
			throw ex;
		}
		catch (RestClientResponseException ex) {
			int status = ex.getStatusCode().value();
			throw new WorkexecClientException("Work Execution response error", status, mapErrorCode(status), ex);
		}
		catch (Exception ex) {
			throw new WorkexecClientException("Work Execution unavailable", 503, "WORKEXEC_UNAVAILABLE", ex);
		}
	}

	private String mapErrorCode(int status) {
		return switch (status) {
			case 400 -> "WORKEXEC_INVALID_REQUEST";
			case 403 -> "WORKEXEC_FORBIDDEN";
			case 500 -> "WORKEXEC_INTERNAL_ERROR";
			case 503 -> "WORKEXEC_UNAVAILABLE";
			default -> status >= 500 ? "WORKEXEC_INTERNAL_ERROR" : "WORKEXEC_INVALID_REQUEST";
		};
	}

}

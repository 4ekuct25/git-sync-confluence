package com.gitlab.sync.confluence.errors;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

/**
 * GET /rest/gitsync/1.0/error-journal
 *
 * Returns the recent Git-Sync error journal as a JSON array
 * (newest first): timestamp, source, context, errorType, message.
 * Readable by any authenticated user, same as the other read endpoints.
 */
@Path("/error-journal")
@Produces(MediaType.APPLICATION_JSON)
public class ErrorJournalRestResource {

    @GET
    public List<Map<String, String>> get() {
        return ErrorJournal.snapshot();
    }
}

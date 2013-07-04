package net.ripe.db.whois.api.whois.rdap;

import com.google.common.collect.Lists;
import com.sun.jersey.api.NotFoundException;
import net.ripe.db.whois.api.whois.ApiResponseHandler;
import net.ripe.db.whois.api.whois.StreamingMarshal;
import net.ripe.db.whois.api.whois.TaggedRpslObject;
import net.ripe.db.whois.api.whois.WhoisStreamingOutput;
import net.ripe.db.whois.api.whois.domain.Parameters;
import net.ripe.db.whois.common.domain.ResponseObject;
import net.ripe.db.whois.common.rpsl.RpslObject;
import net.ripe.db.whois.common.source.SourceContext;
import net.ripe.db.whois.query.domain.QueryCompletionInfo;
import net.ripe.db.whois.query.domain.QueryException;
import net.ripe.db.whois.query.domain.TagResponseObject;
import net.ripe.db.whois.query.handler.QueryHandler;
import net.ripe.db.whois.query.query.Query;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

public class RdapStreamingOutput extends WhoisStreamingOutput {

    private static final int STATUS_TOO_MANY_REQUESTS = 429;

    private final SourceContext sourceContext;
    private final String baseUrl;
    private final String requestUrl;

    private boolean found;

    public RdapStreamingOutput(
            final StreamingMarshal streamingMarshal,
            final QueryHandler queryHandler,
            final Parameters parameters,
            final Query query,
            final InetAddress remoteAddress,
            final int contextId,
            final SourceContext sourceContext,
            final String baseUrl,
            final String requestUrl) {
        super(streamingMarshal, queryHandler, parameters, query, remoteAddress, contextId);
        this.sourceContext = sourceContext;
        this.baseUrl = baseUrl;
        this.requestUrl = requestUrl;
    }

    @Override
    public void write(final OutputStream output) throws IOException {
        streamingMarshal.open(output);

        streamingMarshal.start("");

        // TODO [AK] Crude way to handle tags, but working
        final Queue<RpslObject> rpslObjectQueue = new ArrayDeque<RpslObject>(1);
        final List<TagResponseObject> tagResponseObjects = Lists.newArrayList();
        final Queue<TaggedRpslObject> taggedRpslObjectQueue = new ArrayDeque<TaggedRpslObject>(1);

        try {
            queryHandler.streamResults(query, remoteAddress, contextId, new ApiResponseHandler() {

                @Override
                public void handle(final ResponseObject responseObject) {
                    if (responseObject instanceof TagResponseObject) {
                        tagResponseObjects.add((TagResponseObject) responseObject);
                    } else if (responseObject instanceof RpslObject) {
                        found = true;
                        taggedRpslObjectQueue.add(new TaggedRpslObject((RpslObject)responseObject, tagResponseObjects));
                        tagResponseObjects.clear();
                    }

                    // TODO [AK] Handle related messages
                }
            });

            if (!found) {
                throw new NotFoundException();
            }

            RdapObjectMapper rdapObjectMapper =
                new RdapObjectMapper(baseUrl, requestUrl,
                                     queryHandler, sourceContext,
                                     taggedRpslObjectQueue);
            streamObject(rdapObjectMapper.build());

        } catch (QueryException e) {
            if (e.getCompletionInfo() == QueryCompletionInfo.BLOCKED) {
                throw new WebApplicationException(Response.status(STATUS_TOO_MANY_REQUESTS).build());
            } else {
                throw new RuntimeException("Unexpected result", e);
            }
        } catch (NotFoundException nfe) {
            throw nfe;
        } catch (RuntimeException e) {
            throw new WebApplicationException(
                Response.status(Response.Status.BAD_REQUEST)
                        .entity(e.toString())
                        .build()
            );
        }

        streamingMarshal.close();
    }

    private void streamObject(Object rdapObject) {
        if (rdapObject == null) {
            return;
        }

        streamingMarshal.writeObject(rdapObject);
    }
}

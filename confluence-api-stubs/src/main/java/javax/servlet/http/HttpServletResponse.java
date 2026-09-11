package javax.servlet.http;

import java.io.IOException;
import java.io.PrintWriter;

/** Compile stub for the Servlet API response. */
public interface HttpServletResponse {
    int SC_OK = 200;
    int SC_UNAUTHORIZED = 401;
    void setStatus(int sc) throws IOException;
    void setContentType(String type) throws IOException;
    PrintWriter getWriter() throws IOException;
}

package javax.servlet.http;

import java.io.IOException;
import javax.servlet.ServletException;

/** Compile stub for the Servlet API base class. */
public abstract class HttpServlet {
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        throw new ServletException("GET not supported");
    }

    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        throw new ServletException("POST not supported");
    }
}

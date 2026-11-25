package pe.facturacion.api.modules.admin.panel;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * La vista unificada de comprobantes de UNA empresa ({@code tenants/{id}/comprobantes} del panel) para quien ya
 * resolvió la empresa por su cuenta: el portal de clientes la toma de la membresía de la persona, nunca de un
 * parámetro. Mismos filtros, mismas filas y mismos contadores que el panel (reutiliza {@link AdminAllDocuments} y los
 * filtros de {@link AdminDocumentsController}); solo cambia quién fija la empresa.
 */
@Component
public class AdminTenantUnifiedDocuments {

    private final AdminAllDocuments documents;

    public AdminTenantUnifiedDocuments(AdminAllDocuments documents) {
        this.documents = documents;
    }

    /** Listado paginado de los comprobantes de la empresa (tipo, estado, serie, sucursalId, fechas, textFilter, orden). */
    public ResponseEntity<Object> list(long tenantId, HttpServletRequest request) {
        return AdminDocumentsController.page(documents, AdminDocumentsController.filter(request, tenantId, false), request);
    }

    /** Contadores por tipo y estado de la empresa (mismos filtros salvo tipo y estado). */
    public Map<String, Object> summary(long tenantId, HttpServletRequest request) {
        return documents.summary(AdminDocumentsController.forSummary(AdminDocumentsController.filter(request, tenantId, false)));
    }
}

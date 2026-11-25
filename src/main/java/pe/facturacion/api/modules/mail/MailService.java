package pe.facturacion.api.modules.mail;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import pe.facturacion.api.config.AppProperties;
import pe.facturacion.api.shared.storage.FileStorage;
import pe.facturacion.api.shared.storage.FileStorage.Disk;
import pe.facturacion.api.shared.time.Clock;

/**
 * Envío de correos en segundo plano (en API-PRO los Mailables van a la cola {@code mail}).
 * Sin {@code MAIL_HOST} el correo se guarda en {@code private/mails/} (como el mailer {@code log}
 * de Laravel) y no se escribe en el log, porque puede contener tokens.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final TaskExecutor executor;
    private final AppProperties props;
    private final FileStorage storage;
    private final String host;

    public MailService(ObjectProvider<JavaMailSender> sender, @Qualifier("applicationTaskExecutor") TaskExecutor executor,
            AppProperties props, FileStorage storage, org.springframework.core.env.Environment env) {
        this.sender = sender;
        this.executor = executor;
        this.props = props;
        this.storage = storage;
        this.host = env.getProperty("spring.mail.host", "");
    }

    public void send(String to, String subject, String html) {
        executor.execute(() -> {
            JavaMailSender mailer = host.isBlank() ? null : sender.getIfAvailable();
            if (mailer == null) {
                String name = "mails/" + Clock.nowUtc().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-"
                        + Math.abs(subject.hashCode()) + ".html";
                storage.put(Disk.PRIVATE, name, ("<!-- To: " + to + " | Subject: " + subject + " -->\n" + html).getBytes(StandardCharsets.UTF_8));
                log.info("MAIL_HOST no configurado: correo para {} guardado en {}", to, name);
                return;
            }
            try {
                MimeMessage msg = mailer.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
                helper.setFrom(props.mail().fromAddress(), props.mail().fromName());
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(html, true);
                mailer.send(msg);
            } catch (Exception e) {
                log.warn("No se pudo enviar el correo a {}: {}", to, e.getMessage());
            }
        });
    }
}

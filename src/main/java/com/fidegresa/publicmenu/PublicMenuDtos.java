package com.fidegresa.publicmenu;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Respuestas del menú público (sin sesión) y del registro de clientes desde el QR. */
public final class PublicMenuDtos {
    private PublicMenuDtos() {
    }

    /**
     * pdf, webUrl e invitation son null si el local no los tiene activos. products: la carta oficial del local (en su orden,
     * con los agotados marcados); lista vacía si no tiene productos.
     */
    public record MenuResponse(String slug, BusinessInfo business, BranchInfo branch, PdfCatalog pdf, String webUrl,
                               InvitationInfo invitation, List<ProgramInfo> programs, ConsentInfo consent, List<ProductInfo> products) {
    }

    /** Producto de la carta; imageUrl es null si no tiene foto (es una dirección pública, sin sesión). */
    public record ProductInfo(Long id, String name, String description, BigDecimal price, boolean inStock, String imageUrl) {
    }

    public record BusinessInfo(String name, String primaryColor) {
    }

    public record BranchInfo(String name, String category, String address, String district, String schedule) {
    }

    /** format: "PDF" o "IMAGES". */
    public record PdfCatalog(String format, List<FileInfo> files) {
    }

    public record FileInfo(Long id, String name, String contentType, long sizeBytes, String url) {
    }

    /** imageUrl es null si la invitación no tiene imagen. */
    public record InvitationInfo(String title, String text, String buttonLabel, String imageUrl, Long programId) {
    }

    public record ProgramInfo(Long id, String name, String type, String description, String primaryColor, String accentColor) {
    }

    /** Texto que el cliente acepta al unirse y su versión (se guarda con el cliente). */
    public record ConsentInfo(String version, String text) {
    }

    /** birthday opcional en formato dd/MM. consent debe ser true. */
    /** referralCode: opcional, el código del amigo que lo invitó (enlace /r/:code). */
    public record JoinRequest(String fullName, String email, String phone, String birthday, Boolean consent, Long programId,
                              String referralCode) {
    }

    /** inviteLink: su enlace para invitar amigos (null si falta PUBLIC_WEB_URL). */
    public record JoinResponse(boolean alreadyRegistered, String customerName, CardInfo card, String inviteLink) {
    }

    public record CardInfo(String qrToken, Long programId, String programName, String programType, String primaryColor, String accentColor,
                           Instant expiresAt) {
    }

    /** Enlace del menú público para el QR del panel. url es null si no se configuró PUBLIC_WEB_URL. */
    public record PublicLinkResponse(String slug, String path, String url) {
    }
}

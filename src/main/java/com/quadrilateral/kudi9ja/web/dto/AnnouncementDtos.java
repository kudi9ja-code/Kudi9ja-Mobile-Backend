package com.quadrilateral.kudi9ja.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AnnouncementDtos {

    private AnnouncementDtos() {
    }

    /**
     * One message to every phone.
     *
     * <p>The limits are what a notification shade shows before it cuts the
     * text off, not what the provider would accept.
     */
    public record SendRequest(
            @NotBlank(message = "A title is needed.")
            @Size(max = 65, message = "Keep the title to 65 characters; a phone cuts off the rest.")
            String title,

            @NotBlank(message = "A message is needed.")
            @Size(max = 240, message = "Keep the message to 240 characters; a phone cuts off the rest.")
            String body,

            /** Optional. A public https link to a JPEG or PNG under 1 MB. */
            @Size(max = 1000, message = "That image link is too long.")
            @Pattern(regexp = "^$|^https://\\S+$",
                    message = "The image link must be a public https:// address.")
            String imageUrl) {
    }

    /** How many phones the message is on its way to. */
    public record SendResponse(int devices) {
    }
}

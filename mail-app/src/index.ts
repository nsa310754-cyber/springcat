import type { Env } from "./env";
import { handleApi } from "./api";
import { handleIncomingEmail } from "./email";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname.startsWith("/api/")) {
      return handleApi(request, env, url.pathname);
    }

    // Everything else is the static Gmail-like frontend.
    return env.ASSETS.fetch(request);
  },

  async email(message: ForwardableEmailMessage, env: Env): Promise<void> {
    await handleIncomingEmail(message, env);
  },
};

export type { Env };

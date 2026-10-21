const env = import.meta.env;

/** Defaults are the development setup; every value can be set at build time with a VITE_ variable. */
export const config = {
  apiUrl: ((env.VITE_API_URL as string | undefined) ?? 'http://localhost:5700').replace(/\/+$/, ''),
  authority: (env.VITE_OIDC_AUTHORITY as string | undefined) ?? 'http://localhost:5301/',
  clientId: (env.VITE_OIDC_CLIENT_ID as string | undefined) ?? 'integrationhub-admin',
  scope: (env.VITE_OIDC_SCOPE as string | undefined) ?? 'openid profile',
};

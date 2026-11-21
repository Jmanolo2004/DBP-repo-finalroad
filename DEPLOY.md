# Despliegue de Fidegresa

Todo el sistema está publicado con HTTPS:

| Pieza | Dónde | URL |
|---|---|---|
| Backend (Spring Boot) | **Render** (Docker, plan gratuito) | https://fidegresa-api.onrender.com/api/v1 |
| Base de datos | **Neon** (PostgreSQL administrado, AWS us-east-1) | — |
| Web (React) | **Vercel** | https://frontendfidegresa.vercel.app |
| Disponibilidad | **UptimeRobot** revisa `/api/v1/health` cada 5 minutos | — |

Prueba rápida: `https://fidegresa-api.onrender.com/api/v1/health` responde `{"status":"ok"}`.

Cuenta de prueba: `maria@fidegresa.com` · `Fidegresa2026`.

---

## 1. Base de datos en Neon

1. Crear un proyecto en [neon.tech](https://neon.tech), región **AWS US East 1 (N. Virginia)**.
2. Copiar el *connection string*. De ahí salen la dirección, el usuario y la contraseña.
   Usar la dirección **sin `-pooler`**.

## 2. Backend en Render

El repositorio incluye un `Dockerfile`: compila con Maven y Java 21 y ejecuta el `.jar`.

1. En [render.com](https://render.com): **New → Web Service → Public Git Repository** →
   `https://github.com/Jmanolo2004/DBP-repo-finalroad`.
2. Configuración:

   | Campo | Valor |
   |---|---|
   | Language | Docker |
   | Branch | main |
   | Region | Virginia (US East) |
   | Instance | Free |

3. Variables de entorno (ninguna clave está en el código):

   | Variable | Valor |
   |---|---|
   | `DB_URL` | `jdbc:postgresql://<host-de-neon>/neondb?sslmode=require` |
   | `DB_USERNAME` | usuario de Neon |
   | `DB_PASSWORD` | contraseña de Neon |
   | `JWT_SECRET` | clave aleatoria (`openssl rand -base64 48`) |
   | `CORS_ALLOWED_ORIGINS` | `https://frontendfidegresa.vercel.app` |
   | `SEED_DEMO` | `true` (crea la cuenta de prueba la primera vez) |

4. **Deploy web service**. Render publica de nuevo cada vez que algo entra a `main`.

## 3. Mantenerlo despierto

El plan gratuito de Render se apaga tras 15 minutos sin uso. Un monitor de [UptimeRobot](https://uptimerobot.com) tipo HTTP(s) apunta a
`https://fidegresa-api.onrender.com/api/v1/health` cada 5 minutos, así el servicio responde siempre al instante.

## 4. Web en Vercel

La web usa por defecto `https://fidegresa-api.onrender.com/api/v1`. Opcionalmente se puede definir en Vercel:

| Variable | Valor |
|---|---|
| `VITE_API_URL` | `https://fidegresa-api.onrender.com/api/v1` |
| `VITE_USE_MOCK` | `false` |

Vercel publica de nuevo cada vez que algo entra a `main` del repositorio del frontend.

## 5. App de caja en Expo

En la carpeta `mobile-fidegresa`:

1. En el archivo `.env`:
   ```
   EXPO_PUBLIC_API_URL=https://fidegresa-api.onrender.com/api/v1
   ```
2. Publicar:
   ```bash
   npx eas-cli login
   npx eas-cli update --branch production --message "Conectada al backend"
   ```
3. Abrir el enlace o QR que muestra EAS con **Expo Go**.
4. (Opcional) APK para Android: `npm run build:apk`, con la misma URL en el perfil `preview` de `eas.json`.

## Lista de verificación

- [x] `/api/v1/health` responde `{"status":"ok"}`.
- [x] La web en Vercel inicia sesión y muestra datos reales.
- [x] Sin errores de CORS; JWT funcionando de punta a punta.
- [ ] La app en Expo Go inicia sesión y escanea una tarjeta.

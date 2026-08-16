# 🚀 Hackatón 0 – CS2031

¡Bienvenidos chicos! 🎉
Desde el curso **CS2031** les damos una cordial bienvenida e inicio de ciclo **2025-2**. Sabemos que están llenos de energía y ansiosos por comenzar a colaborar en equipo.

## 🤔 ¿Qué es la Hackatón 0?

Esta es una **primera muestra** del concepto de _hackatones_: un tipo de actividad calificada que, al inicio, muchos podrían odiar 😅…
Pero al final se darán cuenta de que es la que más los prepara para la **industria real**.

👉 Sentarse en equipo, colaborar bajo presión y resolver un problema en poco tiempo es una de las habilidades más valoradas y buscadas en el mundo laboral.

## 👥 Trabajo en equipo

Antes que nada, escoge bien a tu equipo.
No solo se trata de divertirse mientras trabajan, sino de **complementarse**:

- Habrá quienes sean buenos organizando 🗂️
- Otros que lideren 🧭
- Y otros que ejecuten rápido ⚡
- Pero también quienes sean buenos en la parte técnica 💻

Esta hackatón es **trabajo en equipo + Git + GitHub**.
Ni siquiera es necesario programar. El reto está en **la comunicación** 🗣️, en cómo coordinan y se reparten las tareas. Poco a poco irán desarrollando esta habilidad clave.

## 😢 ¿Qué pasa si no viste el video de Git y GitHub?

Que Jorge se apiade de ti 🙏… ¡nah, es broma! 😅 No te preocupes. Si aún no lo revisaste, aquí está el hermoso video preparado con mucho cariño por tus TAs favoritos, Mauricio y tu humilde servidor Gabriel: [👉 Video de introducción a Git y GitHub](https://www.youtube.com/watch?v=8CmZysIzcbc)

Eso sí, ⚠️ es crucial que lo aprendas cuanto antes. Git y GitHub serán herramientas que usarás en todo el curso y en tu vida profesional:

Para colaborar con código 👩‍💻👨‍💻

Para subir tareas y proyectos 📂

Incluso para construir tu propio portafolio de trabajo 💼

Si no lo ves ahora, probablemente sufras un poco en esta hackatón 🔥… ¡pero tranquilo! Al menos estás en equipo, así que siempre puedes pedir ayuda a tus compañeros para ir avanzando. Recuerda: la unión hace la fuerza 💪

¡Genial! Aquí te dejo la **parte del reto** con un ejemplo claro de cómo se vería un **conflicto en Git** dentro de `index.html`.

## 📜 El reto

Bueno, ahora sí que tienes el _lore_ suficiente para lanzarte a la hackatón. ¡Vamos con el reto! 💪

Resulta que un TA (no diremos nombre 🤫) estuvo moviendo los archivos, alterando el git y **rompió el proyecto de la página web** para cada equipo.

🎯 **Tu objetivo:** corregir los errores y restaurar el proyecto a su estado funcional, de modo que el equipo pueda tener lista su **primera página web** y finalmente **desplegarla en GitHub Pages**. 🚀

### 👑 Organización del equipo

- Elijan un **líder de equipo**, que será el encargado de crear y asignar los issues.
- Cada integrante trabajará en **su propia rama individual** (`feat/member-nombre`) y abrirá un PR para que el líder lo revise y acepte.
- Habrá **conflictos intencionales** en los archivos (por ejemplo en la lista de integrantes y entre diferentes arquitecturas de CSS), que deberán resolver colaborativamente.

### ✅ Pasos principales

1. **Seleccionar un líder de equipo.**

- Crear un nuevo repositorio usando la plantilla `cs2031-2025-2-hackathon-0` y asegurandose de incluir todas las ramas.
- Dar acceso de colaborador a todos los miembros del equipo.
- Abre un issue “Checklist del equipo”.
- Coordina y supervisa el progreso de cada miembro.

2. **Checklist del equipo (issues a crear por el líder):**
   - **#1 Datos personales (PR por persona)**
     - Cada integrante edita la sección de su tarjeta personal en `index.html` en su **propia rama** (`feat/member-nombre`).
     - Reemplaza en el div `<div class="team-card__content">` con tus datos propios.
     - **Conflicto esperado:** varios editan la misma línea → deberán resolverlo conservando todos los nombres. 
     - Debe reemplazar los comentarios y datos de ejemplo por sus datos reales:
       - Foto de perfil (`src` y `alt` en `<img>`)
       - Nombre completo (`<h3>`)
       - Especialidad o rol (`<p>`)
       - Links de GitHub y LinkedIn (`href`)
     - Ejemplo:
       ```html
       <div class="team-card__content">
         <!-- Aquí tu nombre completo -->
         <h3 class="team-card__name">Sparky</h3>
         <!-- Aquí tu especialidad o rol (ej: Frontend Developer, Backend Developer, etc.) -->
         <p class="team-card__role">Frontend Developer</p>
         <div class="team-card__social">
           <!-- Aquí tu link de GitHub (reemplaza el # con tu URL) -->
           <a
             href="https://github.com/CS2031-DBP"
             class="team-card__social-link team-card__social-link--github"
           >
             <i class="fab fa-github"></i>
           </a>
           <!-- Aquí tu link de LinkedIn (reemplaza el # con tu URL) -->
           <a
             href="https://www.linkedin.com/school/utec-universidad-de-ingenieria-y-tecnologia/"
             class="team-card__social-link team-card__social-link--linkedin"
           >
             <i class="fab fa-linkedin"></i>
           </a>
         </div>
       </div>
       ```
   - **#2 CSS modular (1 PR)**
     - La rama `clean-css` tiene el CSS dividido en archivos modulares (`footer.css`, `header.css`, `main.css`, etc.) con imports en `index.css`.
     - La rama `main` tiene todo el CSS en un archivo monolítico `index.css`.
     - **Conflicto esperado:** Al hacer merge, tendrán dos versiones completamente diferentes del sistema de estilos que deberán integrar correctamente.
   - **#3 Navbar (1 PR)**
     - Corregir los enlaces del `<nav>` usando la rama `navbar-fix`, para que los links apunten a las secciones correctas.
   - **Publicado en Pages**
     - Activar GitHub Pages desde `main`.
     - Validar que el sitio funcione y compartir la URL.
     - Referencia: [Configurar la fuente de publicación para GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site)

---

### ⚡ Ejemplo de conflicto en `index.html`

Cuando dos integrantes editan la misma línea, Git genera un conflicto como este:

```html
<div class="team-card">
  <<<<<<< HEAD
  <h3 class="team-card__name">María López</h3>
  <p class="team-card__role">Frontend Developer</p>
  <a href="https://github.com/marialopez">GitHub</a>
  =======
  <h3 class="team-card__name">José Pérez</h3>
  <p class="team-card__role">Backend Developer</p>
  <a href="https://github.com/joseperez">GitHub</a>
  >>>>>>> feat/member-jose
</div>
```

👉 La tarea del equipo es **resolverlo manualmente**, eliminando los marcadores y dejando un resultado correcto, por ejemplo:

```html
<div class="team-card">
  <h3 class="team-card__name">María López</h3>
  <p class="team-card__role">Frontend Developer</p>
  <a href="https://github.com/marialopez">GitHub</a>
  <h3 class="team-card__name">José Pérez</h3>
  <p class="team-card__role">Backend Developer</p>
  <a href="https://github.com/joseperez">GitHub</a>
</div>
```

💡 Recuerden: **Git es como viajar en el tiempo** ⏳. Pueden traer de vuelta partes de commits anteriores y combinarlas con el presente. El reto no es programar mucho, sino **resolver conflictos, coordinarse y documentar todo con ramas y PRs**.

La página es estática (HTML + CSS con principios BEM). El desafío está en **organizar el trabajo, arreglar conflictos y lograr un deploy funcional en equipo**.

Espero que el reto les haya gustado y que hayan aprendido sobre la importancia de la colaboración en el desarrollo de software. Con cariño, su TA a distancia Gabriel Romero.

P.D. Se vienen cositas interesantes en el próximo reto.

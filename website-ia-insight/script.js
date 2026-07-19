/* IA-INSIGHT — interactions du site vitrine */

/* Menu mobile */
const burger = document.getElementById('nav-burger');
const links  = document.getElementById('nav-links');
burger.addEventListener('click', () => links.classList.toggle('open'));
links.querySelectorAll('a').forEach(a =>
  a.addEventListener('click', () => links.classList.remove('open'))
);

/* Bascule tarifs mensuel / annuel */
const billingSwitch = document.getElementById('billing-switch');
const labelMonthly  = document.getElementById('label-monthly');
const labelYearly   = document.getElementById('label-yearly');

billingSwitch.addEventListener('click', () => {
  const yearly = billingSwitch.getAttribute('aria-checked') !== 'true';
  billingSwitch.setAttribute('aria-checked', String(yearly));
  labelMonthly.classList.toggle('active', !yearly);
  labelYearly.classList.toggle('active', yearly);
  document.querySelectorAll('.price-num').forEach(el => {
    el.textContent = yearly ? el.dataset.yearly : el.dataset.monthly;
  });
});

/* ============================================================
   CHATBOT — assistant scripté 100% client-side
   ============================================================ */
(function () {
  /* URL du backend IA (ai-service via Kong), ex: 'https://api.ia-insightservices.fr/public/chatbot'.
     Vide = assistant scripté 100% local. Si l'API est configurée mais injoignable,
     le bot retombe automatiquement sur les réponses scriptées. */
  const CHATBOT_API = '';

  const toggle   = document.getElementById('chat-toggle');
  const panel    = document.getElementById('chat-panel');
  const closeBtn = document.getElementById('chat-close');
  const messages = document.getElementById('chat-messages');
  const quick    = document.getElementById('chat-quick');
  const form     = document.getElementById('chat-form');
  const input    = document.getElementById('chat-input');
  const badge    = document.getElementById('chat-badge');

  const CONTACT = `<a href="tel:+33675717743">📞 06 75 71 77 43</a> · <a href="mailto:demo@ia-insightservices.fr">demo@ia-insightservices.fr</a>`;

  const INTENTS = [
    {
      keys: ['tarif', 'prix', 'coût', 'cout', 'combien', 'abonnement', 'euro'],
      reply: `Nos offres démarrent à <strong>29€/consultant/mois</strong> (Essentiel), <strong>49€</strong> pour Croissance — la plus choisie, avec facturation et assistant IA — et du sur-mesure en Enterprise. <strong>-20%</strong> en facturation annuelle. <a href="#tarifs">Voir le détail des offres →</a>`,
      chips: ['Réserver une démo', 'Que fait la plateforme ?']
    },
    {
      keys: ['démo', 'demo', 'essai', 'tester', 'rdv', 'rendez-vous'],
      reply: `Avec plaisir ! La démo dure <strong>30 minutes</strong>, sur vos cas d'usage réels, sans engagement. Écrivez-nous à <a href="mailto:demo@ia-insightservices.fr?subject=Demande%20de%20d%C3%A9mo%20IA-INSIGHT">demo@ia-insightservices.fr</a> ou appelez le <a href="tel:+33675717743">06 75 71 77 43</a> — un humain décroche, promis.`,
      chips: ['Voir les tarifs', 'Audit IA, c\'est quoi ?']
    },
    {
      keys: ['relance', 'impayé', 'impaye', 'recouvrement', 'dso', 'retard de paiement', 'ne paie pas', 'mise en demeure'],
      reply: `Les <strong>relances d'impayés sont automatiques</strong> : rappel courtois à J+3 après l'échéance, relance ferme à J+15, mise en demeure à J+30 (art. L441-10). Tout est tracé et s'arrête dès l'encaissement — votre trésorerie rentre sans y penser. <a href="#plateforme">En savoir plus →</a>`,
      chips: ['Réserver une démo', 'Voir les tarifs']
    },
    {
      keys: ['note de frais', 'frais', 'ocr', 'justificatif', 'cra', 'facture', 'congé', 'conge', 'absence', 'plateforme', 'fonctionnalit', 'module', 'dashboard', 'pilotage', 'marge'],
      reply: `IA-INSIGHT automatise tout le back-office d'une ESN : <strong>notes de frais dictées en langage naturel</strong> (avec OCR), <strong>factures avec relances d'impayés automatiques</strong>, <strong>CRA temps réel</strong> (retardataires relancés automatiquement), congés, et un <strong>dashboard direction</strong> exact — CA au TJM réel de chaque mission, marge par consultant et par projet. <a href="#plateforme">Découvrir les modules →</a>`,
      chips: ['Voir les tarifs', 'Réserver une démo']
    },
    {
      keys: ['audit', 'gouvernance', 'ai act', 'conseil', 'prestation', 'accompagnement', 'rag sur mesure', 'projet ia'],
      reply: `Au-delà du logiciel, nous proposons : <strong>Audit IA</strong> (dès 4 900€ — feuille de route en 2-3 semaines), <strong>Gouvernance IA</strong> (dès 9 900€ — conformité AI Act &amp; RGPD) et de l'<strong>ingénierie IA sur mesure</strong> (RAG, agents, TJM dès 950€). <a href="#services">Voir les prestations →</a>`,
      chips: ['Réserver une démo', 'Et la sécurité ?']
    },
    {
      keys: ['sécurit', 'securit', 'rgpd', 'donnée', 'donnee', 'confidentiel', 'hébergement', 'hebergement'],
      reply: `Vos données sont hébergées en <strong>Union européenne</strong>, isolées par organisation, jamais utilisées pour entraîner des modèles. Garde-fous IA intégrés (anti-injection, rate-limit) et traçabilité prête pour l'<strong>AI Act</strong>. <a href="#securite">En savoir plus →</a>`,
      chips: ['Voir les tarifs', 'Réserver une démo']
    },
    {
      keys: ['contact', 'téléphone', 'telephone', 'appel', 'mail', 'email', 'joindre', 'humain'],
      reply: `Le plus simple : ${CONTACT}. Pour les devis et projets : <a href="mailto:adv@ia-insightservices.fr">adv@ia-insightservices.fr</a> — réponse sous 24h ouvrées.`,
      chips: ['Réserver une démo', 'Voir les tarifs']
    },
    {
      keys: ['bonjour', 'salut', 'hello', 'bonsoir', 'coucou', 'hey'],
      reply: `Bonjour ! 👋 Ravi de vous voir. Je peux vous parler de la plateforme, des tarifs, de nos services IA… ou vous mettre en relation avec un humain. Que puis-je faire pour vous ?`,
      chips: ['Que fait la plateforme ?', 'Voir les tarifs', 'Réserver une démo']
    },
    {
      keys: ['merci', 'parfait', 'super', 'top', 'génial'],
      reply: `Avec plaisir ! 😊 Si vous voulez aller plus loin, la démo de 30 minutes est le meilleur moyen de voir la plateforme en vrai. ${CONTACT}`,
      chips: ['Réserver une démo']
    }
  ];

  const FALLBACK = {
    reply: `Bonne question — et je préfère qu'un humain vous réponde précisément plutôt que d'inventer. 🙂 Contactez-nous : ${CONTACT}. En attendant, je peux vous parler des tarifs, de la plateforme ou de nos services IA.`,
    chips: ['Voir les tarifs', 'Que fait la plateforme ?', 'Audit IA, c\'est quoi ?']
  };

  function addMessage(html, who) {
    const div = document.createElement('div');
    div.className = 'chat-msg ' + who;
    div.innerHTML = html;
    messages.appendChild(div);
    messages.scrollTop = messages.scrollHeight;
    return div;
  }

  function setChips(chips) {
    quick.innerHTML = '';
    (chips || []).forEach(label => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'chat-chip';
      b.textContent = label;
      b.addEventListener('click', () => send(label));
      quick.appendChild(b);
    });
  }

  function renderIntent(el, text) {
    const q = text.toLowerCase();
    const intent = INTENTS.find(i => i.keys.some(k => q.includes(k))) || FALLBACK;
    el.innerHTML = intent.reply;
    setChips(intent.chips);
    messages.scrollTop = messages.scrollHeight;
    // Fermer le chat quand on clique un lien d'ancre pour voir la section
    el.querySelectorAll('a[href^="#"]').forEach(a =>
      a.addEventListener('click', () => panel.classList.add('hidden'))
    );
  }

  async function askApi(text) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 12000);
    try {
      const res = await fetch(CHATBOT_API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: text }),
        signal: ctrl.signal
      });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const data = await res.json();
      if (!data.reply) throw new Error('empty reply');
      return data.reply;
    } finally {
      clearTimeout(timer);
    }
  }

  function answer(text) {
    const typing = addMessage('<span class="chat-typing"><i></i><i></i><i></i></span>', 'bot');
    setChips([]);
    if (CHATBOT_API) {
      askApi(text)
        .then(reply => {
          typing.textContent = reply;  // texte brut — la réponse LLM n'est jamais interprétée en HTML
          setChips(['Réserver une démo', 'Voir les tarifs', 'Parler à un humain']);
          messages.scrollTop = messages.scrollHeight;
        })
        .catch(() => renderIntent(typing, text));
      return;
    }
    setTimeout(() => renderIntent(typing, text), 600 + Math.random() * 400);
  }

  function send(text) {
    if (!text.trim()) return;
    addMessage(text.replace(/[<>&]/g, c => ({'<':'&lt;','>':'&gt;','&':'&amp;'}[c])), 'user');
    answer(text);
  }

  let opened = false;
  function openChat() {
    panel.classList.remove('hidden');
    badge.style.display = 'none';
    if (!opened) {
      opened = true;
      addMessage(`Bonjour ! 👋 Je suis l'assistant IA-INSIGHT. Tarifs, démo, services IA — posez-moi vos questions, ou choisissez ci-dessous.`, 'bot');
      setChips(['Que fait la plateforme ?', 'Voir les tarifs', 'Réserver une démo']);
    }
    input.focus();
  }

  toggle.addEventListener('click', () =>
    panel.classList.contains('hidden') ? openChat() : panel.classList.add('hidden')
  );
  closeBtn.addEventListener('click', () => panel.classList.add('hidden'));
  form.addEventListener('submit', e => {
    e.preventDefault();
    send(input.value);
    input.value = '';
  });
})();

/* Apparition au scroll */
const observer = new IntersectionObserver(entries => {
  entries.forEach(entry => {
    if (entry.isIntersecting) {
      entry.target.classList.add('visible');
      observer.unobserve(entry.target);
    }
  });
}, { threshold: 0.12 });

document.querySelectorAll('.reveal').forEach(el => observer.observe(el));

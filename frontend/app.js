const video = document.getElementById('video');
const canvas = document.getElementById('canvas');
const startBtn = document.getElementById('start-camera');
const captureBtn = document.getElementById('capture');
const fileInput = document.getElementById('file-input');
const paymentSelect = document.getElementById('payment-mode');
const preview = document.getElementById('preview');
const cameraStatus = document.getElementById('camera-status');
const uploadStatus = document.getElementById('upload-status');
const apiUrlInput = document.getElementById('api-url');

const DEFAULT_ENDPOINT = 'http://localhost:8081/receipts/upload';
apiUrlInput.value = localStorage.getItem('uploadEndpoint') || DEFAULT_ENDPOINT;

apiUrlInput.addEventListener('change', () => {
  const val = apiUrlInput.value.trim();
  if (val) localStorage.setItem('uploadEndpoint', val);
});

let stream;

async function startCamera() {
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
    video.srcObject = stream;
    captureBtn.disabled = false;
    cameraStatus.textContent = 'Caméra active';
    cameraStatus.className = 'status ok';
  } catch (e) {
    cameraStatus.textContent = 'Impossible d’accéder à la caméra : ' + e.message;
    cameraStatus.className = 'status err';
  }
}

function stopCamera() {
  if (stream) {
    stream.getTracks().forEach(t => t.stop());
    stream = null;
  }
  captureBtn.disabled = true;
}

function dataUrlToFile(dataUrl, filename) {
  const arr = dataUrl.split(',');
  const mime = arr[0].match(/:(.*?);/)[1];
  const bstr = atob(arr[1]);
  let n = bstr.length;
  const u8arr = new Uint8Array(n);
  while (n--) {
    u8arr[n] = bstr.charCodeAt(n);
  }
  return new File([u8arr], filename, { type: mime });
}

async function capturePhoto() {
  if (!stream) return;
  const ctx = canvas.getContext('2d');
  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;
  ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
  const dataUrl = canvas.toDataURL('image/jpeg', 0.9);
  const file = dataUrlToFile(dataUrl, 'receipt.jpg');
  showPreview(dataUrl);
  await uploadFile(file);
}

function showPreview(src, isPdf = false) {
  preview.innerHTML = '';
  if (isPdf) {
    const embed = document.createElement('embed');
    embed.src = src;
    embed.type = 'application/pdf';
    preview.appendChild(embed);
  } else {
    const img = document.createElement('img');
    img.src = src;
    preview.appendChild(img);
  }
}

async function uploadFile(file) {
  const endpoint = apiUrlInput.value.trim() || DEFAULT_ENDPOINT;
  const formData = new FormData();
  formData.append('file', file);
  if (paymentSelect && paymentSelect.value) {
    formData.append('paymentMode', paymentSelect.value);
  }
  uploadStatus.textContent = 'Upload en cours…';
  uploadStatus.className = 'status';

  try {
    const res = await fetch(endpoint, { method: 'POST', body: formData });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const json = await res.json().catch(() => ({}));
    uploadStatus.textContent = 'Upload réussi';
    uploadStatus.className = 'status ok';
    if (json.id) {
      uploadStatus.textContent += ` (id: ${json.id})`;
    }
  } catch (e) {
    uploadStatus.textContent = 'Erreur upload : ' + e.message;
    uploadStatus.className = 'status err';
  }
}

startBtn.addEventListener('click', () => {
  if (stream) {
    stopCamera();
    startCamera();
  } else {
    startCamera();
  }
});

captureBtn.addEventListener('click', () => {
  capturePhoto();
});

fileInput.addEventListener('change', async (e) => {
  const file = e.target.files?.[0];
  if (!file) return;
  const isPdf = file.type === 'application/pdf';

  if (!isPdf) {
    const reader = new FileReader();
    reader.onload = () => showPreview(reader.result);
    reader.readAsDataURL(file);
  } else {
    const url = URL.createObjectURL(file);
    showPreview(url, true);
  }
  await uploadFile(file);
});

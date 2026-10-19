const loginView = document.querySelector('#loginView');
const appView = document.querySelector('#appView');
const loginForm = document.querySelector('#loginForm');
const loginError = document.querySelector('#loginError');
const productRows = document.querySelector('#productRows');
const productSearch = document.querySelector('#productSearch');
const productDialog = document.querySelector('#productDialog');
const productForm = document.querySelector('#productForm');
const productError = document.querySelector('#productError');

let products = [];

async function api(path, options = {}) {
  const response = await fetch(path, {
    credentials: 'same-origin',
    ...options
  });
  const payload = await response.json();
  if (!response.ok) {
    throw new Error(payload.error || 'Something went wrong. Please try again.');
  }
  return payload;
}

function showError(element, message) {
  element.textContent = message;
  element.hidden = false;
}

function clearError(element) {
  element.textContent = '';
  element.hidden = true;
}

function showDashboard(username) {
  loginView.hidden = true;
  appView.hidden = false;
  const initial = username.charAt(0).toUpperCase();
  document.querySelector('#profileName').textContent =
    username.charAt(0).toUpperCase() + username.slice(1);
  document.querySelectorAll('.avatar, .top-avatar').forEach(avatar => {
    avatar.textContent = initial;
  });
  document.querySelector('#todayLabel').textContent = new Intl.DateTimeFormat('en', {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    year: 'numeric'
  }).format(new Date());
  loadProducts();
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, character => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  })[character]);
}

function currency(value) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(value);
}

function renderProducts() {
  const query = productSearch.value.trim().toLowerCase();
  const visibleProducts = products.filter(product =>
    [product.name, product.sku, product.category].some(value => value.toLowerCase().includes(query))
  );

  productRows.innerHTML = visibleProducts.map((product, index) => {
    const low = product.quantity <= 5;
    const icon = ['◈', '◉', '▧', '▤', '⌘', '◇'][index % 6];
    return `<tr>
      <td><div class="product-cell"><span class="product-thumb tone-${index % 4}" aria-hidden="true">${icon}</span><span class="product-name"><strong>${escapeHtml(product.name)}</strong><small>Added to your catalog</small></span></div></td>
      <td class="sku-cell">${escapeHtml(product.sku)}</td>
      <td><span class="category-chip">${escapeHtml(product.category)}</span></td>
      <td class="quantity-cell">${product.quantity} <span class="unit-label">units</span></td>
      <td class="price-cell">${currency(product.price)}</td>
      <td><span class="status-pill${low ? ' low' : ''}">${low ? 'Low stock' : 'In stock'}</span></td>
    </tr>`;
  }).join('');

  document.querySelector('#emptyState').hidden = visibleProducts.length !== 0;
  document.querySelector('#tableCount').textContent = `${products.length} ${products.length === 1 ? 'item' : 'items'}`;
  document.querySelector('#shownCount').textContent = visibleProducts.length;
  document.querySelector('#footerCount').textContent = products.length;
  document.querySelector('#navProductCount').textContent = products.length;
  document.querySelector('#totalProducts').textContent = products.length;
  document.querySelector('#totalUnits').textContent =
    products.reduce((total, product) => total + product.quantity, 0).toLocaleString();
  document.querySelector('#lowStock').textContent =
    products.filter(product => product.quantity <= 5).length;
  document.querySelector('#stockValue').textContent = currency(
    products.reduce((total, product) => total + product.quantity * product.price, 0)
  );
}

async function loadProducts() {
  try {
    products = await api('/api/products');
    renderProducts();
  } catch (error) {
    showError(loginError, error.message);
    loginView.hidden = false;
    appView.hidden = true;
  }
}

loginForm.addEventListener('submit', async event => {
  event.preventDefault();
  clearError(loginError);
  const formData = new FormData(loginForm);
  try {
    const account = await api('/api/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(formData)
    });
    showDashboard(account.username);
  } catch (error) {
    showError(loginError, error.message);
  }
});

document.querySelector('#logoutButton').addEventListener('click', async () => {
  try {
    await api('/api/logout', { method: 'POST' });
    products = [];
    appView.hidden = true;
    loginView.hidden = false;
    loginForm.querySelector('[name="password"]').focus();
  } catch (error) {
    window.alert(error.message);
  }
});

document.querySelector('#addProductButton').addEventListener('click', () => {
  clearError(productError);
  productForm.reset();
  productDialog.showModal();
});

document.querySelector('#closeDialog').addEventListener('click', () => productDialog.close());
document.querySelector('#cancelDialog').addEventListener('click', () => productDialog.close());
productSearch.addEventListener('input', renderProducts);

productForm.addEventListener('submit', async event => {
  event.preventDefault();
  clearError(productError);
  const formData = new FormData(productForm);
  try {
    const product = await api('/api/products', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(formData)
    });
    products.push(product);
    renderProducts();
    productDialog.close();
  } catch (error) {
    showError(productError, error.message);
  }
});

document.querySelectorAll('.nav-link').forEach(link => {
  link.addEventListener('click', () => {
    document.querySelectorAll('.nav-link').forEach(item => {
      item.classList.remove('active');
      item.removeAttribute('aria-current');
    });
    link.classList.add('active');
    link.setAttribute('aria-current', 'page');
  });
});

api('/api/session')
  .then(account => showDashboard(account.username))
  .catch(() => {
    loginView.hidden = false;
    appView.hidden = true;
  });

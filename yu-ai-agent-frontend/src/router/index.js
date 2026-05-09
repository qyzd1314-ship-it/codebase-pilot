import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('../views/Home.vue'),
    meta: {
      title: 'Codebase Agent Workspace'
    }
  },
  {
    path: '/repos',
    name: 'Repositories',
    component: () => import('../views/Repositories.vue'),
    meta: {
      title: 'Codebase Agent - Repositories'
    }
  },
  {
    path: '/tasks',
    name: 'TaskWorkbench',
    component: () => import('../views/TaskWorkbench.vue'),
    meta: {
      title: 'Codebase Agent - Tasks'
    }
  },
  {
    path: '/tasks/new',
    name: 'TaskCreate',
    component: () => import('../views/TaskWorkbench.vue'),
    meta: {
      title: 'Codebase Agent - Create Task'
    }
  },
  {
    path: '/tasks/:taskId',
    name: 'TaskDetail',
    component: () => import('../views/TaskWorkbench.vue'),
    meta: {
      title: 'Codebase Agent - Task Detail'
    }
  },
  {
    path: '/eval',
    name: 'EvalPanel',
    component: () => import('../views/EvalPanel.vue'),
    meta: {
      title: 'Codebase Agent - Eval Panel'
    }
  },
  {
    path: '/love-master',
    name: 'LoveMaster',
    component: () => import('../views/LoveMaster.vue'),
    meta: {
      title: 'Yu AI Agent - Love Master'
    }
  },
  {
    path: '/super-agent',
    name: 'SuperAgent',
    component: () => import('../views/SuperAgent.vue'),
    meta: {
      title: 'Yu AI Agent - Legacy Super Agent'
    }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  if (to.meta.title) {
    document.title = to.meta.title
  }
  next()
})

export default router
